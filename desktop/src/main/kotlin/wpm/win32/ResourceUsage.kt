package wpm.win32

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import wpm.logger

private val log = logger("usage")

/** How much the app and its engines (Chromium's helper processes) use right now. */
data class ResourceUsage(val cpuPercent: Double, val memoryMb: Long, val gpuPercent: Double?, val processes: Int)

@Suppress("FunctionName")
private interface PdhEx : StdCallLibrary {
    fun PdhOpenQueryW(dataSource: String?, userData: Pointer?, query: PointerByReference): Int
    fun PdhAddEnglishCounterW(query: Pointer, path: String, userData: Pointer?, counter: PointerByReference): Int
    fun PdhCollectQueryData(query: Pointer): Int
    fun PdhGetFormattedCounterArrayW(counter: Pointer, format: Int, bufferSize: IntByReference, itemCount: IntByReference, items: Pointer?): Int
    fun PdhCloseQuery(query: Pointer): Int

    companion object {
        val INSTANCE: PdhEx = Native.load("pdh", PdhEx::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
}

@Suppress("FunctionName")
private interface Kernel32Mem : StdCallLibrary {
    fun K32GetProcessMemoryInfo(process: WinNT.HANDLE, counters: Pointer, size: Int): Boolean

    companion object {
        val INSTANCE: Kernel32Mem = Native.load("kernel32", Kernel32Mem::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
}

/**
 * Samples CPU, memory and GPU use of this process and its child processes. Call [sample]
 * every second or two; CPU and GPU are averaged since the previous call.
 */
class ResourceMonitor : AutoCloseable {
    private val cores = Runtime.getRuntime().availableProcessors()
    private var lastCpu = mutableMapOf<Long, Long>() // pid -> total CPU time (ns)
    private var lastTime = System.nanoTime()
    private var gpuQuery: Pointer? = null
    private var gpuCounter: Pointer? = null

    init {
        runCatching {
            val query = PointerByReference()
            check(PdhEx.INSTANCE.PdhOpenQueryW(null, null, query) == 0)
            val counter = PointerByReference()
            check(PdhEx.INSTANCE.PdhAddEnglishCounterW(query.value, "\\GPU Engine(*)\\Utilization Percentage", null, counter) == 0)
            gpuQuery = query.value
            gpuCounter = counter.value
            PdhEx.INSTANCE.PdhCollectQueryData(query.value) // GPU use needs two samples
        }.onFailure { log.info("GPU use isn't available: $it") }
    }

    private fun processes(): List<ProcessHandle> = listOf(ProcessHandle.current()) + ProcessHandle.current().descendants().toList()

    fun sample(): ResourceUsage {
        val procs = processes()
        val now = System.nanoTime()
        val cpu = procs.associate { p -> p.pid() to (p.info().totalCpuDuration().map { it.toNanos() }.orElse(0L)) }
        val used = cpu.entries.sumOf { (pid, total) -> (total - (lastCpu[pid] ?: total)).coerceAtLeast(0) }
        val elapsed = (now - lastTime).coerceAtLeast(1)
        lastCpu = cpu.toMutableMap()
        lastTime = now
        val memory = procs.sumOf { workingSet(it.pid()) }
        return ResourceUsage(100.0 * used / elapsed / cores, memory / (1024 * 1024), gpu(procs.map { it.pid() }.toSet()), procs.size)
    }

    private fun workingSet(pid: Long): Long {
        val handle = Kernel32.INSTANCE.OpenProcess(WinNT.PROCESS_QUERY_LIMITED_INFORMATION, false, pid.toInt()) ?: return 0
        try {
            val counters = Memory(72) // PROCESS_MEMORY_COUNTERS on 64-bit Windows
            counters.setInt(0, 72)
            return if (Kernel32Mem.INSTANCE.K32GetProcessMemoryInfo(handle, counters, 72)) counters.getLong(16) else 0 // WorkingSetSize
        } finally {
            Kernel32.INSTANCE.CloseHandle(handle)
        }
    }

    /** Like Task Manager: per engine type, the use of all our processes added up; the busiest type counts. */
    private fun gpu(pids: Set<Long>): Double? {
        val query = gpuQuery ?: return null
        val counter = gpuCounter ?: return null
        val pdh = PdhEx.INSTANCE
        if (pdh.PdhCollectQueryData(query) != 0) return null
        val size = IntByReference(0)
        val count = IntByReference(0)
        pdh.PdhGetFormattedCounterArrayW(counter, PDH_FMT_DOUBLE, size, count, null) // asks for the buffer size
        if (size.value <= 0) return 0.0
        val buffer = Memory(size.value.toLong())
        if (pdh.PdhGetFormattedCounterArrayW(counter, PDH_FMT_DOUBLE, size, count, buffer) != 0) return null
        val byEngine = mutableMapOf<String, Double>()
        for (i in 0 until count.value) {
            // PDH_FMT_COUNTERVALUE_ITEM_W: LPWSTR name; { DWORD status; (padding); double value }
            val base = i * 24L
            val name = buffer.getPointer(base)?.getWideString(0) ?: continue // e.g. pid_1234_luid_..._phys_0_eng_0_engtype_3D
            val pid = name.substringAfter("pid_", "").substringBefore('_').toLongOrNull() ?: continue
            if (pid !in pids || buffer.getInt(base + 8) != 0) continue
            val type = name.substringAfter("engtype_", "other")
            byEngine[type] = (byEngine[type] ?: 0.0) + buffer.getDouble(base + 16)
        }
        return byEngine.values.maxOrNull()?.coerceIn(0.0, 100.0) ?: 0.0
    }

    override fun close() {
        gpuQuery?.let { PdhEx.INSTANCE.PdhCloseQuery(it) }
        gpuQuery = null
    }

    private companion object {
        const val PDH_FMT_DOUBLE = 0x00000200
    }
}
