package wpm.services

import java.time.ZoneId

/**
 * Large cities to pick a location from, so sunrise and sunset work without typing coordinates
 * or asking the internet. Any other place can be entered by latitude and longitude.
 */
val CITIES: List<Place> = """
    Abu Dhabi|24.45|54.38
    Accra|5.60|-0.19
    Addis Ababa|9.03|38.74
    Adelaide|-34.93|138.60
    Ahmedabad|23.02|72.57
    Algiers|36.75|3.06
    Almaty|43.24|76.89
    Amsterdam|52.37|4.90
    Anchorage|61.22|-149.90
    Ankara|39.93|32.86
    Athens|37.98|23.73
    Atlanta|33.75|-84.39
    Auckland|-36.85|174.76
    Baghdad|33.31|44.37
    Baku|40.41|49.87
    Bangalore|12.97|77.59
    Bangkok|13.76|100.50
    Barcelona|41.39|2.17
    Beijing|39.90|116.41
    Beirut|33.89|35.50
    Belgrade|44.79|20.45
    Berlin|52.52|13.40
    Bogotá|4.71|-74.07
    Boston|42.36|-71.06
    Brisbane|-27.47|153.03
    Brussels|50.85|4.35
    Bucharest|44.43|26.10
    Budapest|47.50|19.04
    Buenos Aires|-34.60|-58.38
    Cairo|30.04|31.24
    Calgary|51.05|-114.07
    Cape Town|-33.92|18.42
    Caracas|10.48|-66.90
    Casablanca|33.57|-7.59
    Chennai|13.08|80.27
    Chicago|41.88|-87.63
    Copenhagen|55.68|12.57
    Dallas|32.78|-96.80
    Dar es Salaam|-6.79|39.21
    Delhi|28.61|77.21
    Denver|39.74|-104.99
    Dhaka|23.81|90.41
    Doha|25.29|51.53
    Dubai|25.20|55.27
    Dublin|53.35|-6.26
    Edinburgh|55.95|-3.19
    Frankfurt|50.11|8.68
    Geneva|46.20|6.14
    Guangzhou|23.13|113.26
    Hamburg|53.55|9.99
    Hanoi|21.03|105.85
    Havana|23.11|-82.37
    Helsinki|60.17|24.94
    Ho Chi Minh City|10.82|106.63
    Hong Kong|22.32|114.17
    Honolulu|21.31|-157.86
    Houston|29.76|-95.37
    Hyderabad|17.39|78.49
    Istanbul|41.01|28.98
    Jakarta|-6.21|106.85
    Jerusalem|31.77|35.21
    Johannesburg|-26.20|28.05
    Kabul|34.56|69.21
    Karachi|24.86|67.01
    Kathmandu|27.72|85.32
    Kyiv|50.45|30.52
    Kolkata|22.57|88.36
    Kuala Lumpur|3.14|101.69
    Lagos|6.52|3.38
    Lahore|31.55|74.34
    Lima|-12.05|-77.04
    Lisbon|38.72|-9.14
    London|51.51|-0.13
    Los Angeles|34.05|-118.24
    Madrid|40.42|-3.70
    Manila|14.60|120.98
    Melbourne|-37.81|144.96
    Mexico City|19.43|-99.13
    Miami|25.76|-80.19
    Milan|45.46|9.19
    Minneapolis|44.98|-93.27
    Montevideo|-34.90|-56.16
    Montreal|45.50|-73.57
    Moscow|55.76|37.62
    Mumbai|19.08|72.88
    Munich|48.14|11.58
    Nairobi|-1.29|36.82
    New York|40.71|-74.01
    Osaka|34.69|135.50
    Oslo|59.91|10.75
    Ottawa|45.42|-75.70
    Paris|48.86|2.35
    Perth|-31.95|115.86
    Philadelphia|39.95|-75.17
    Phoenix|33.45|-112.07
    Prague|50.08|14.44
    Pune|18.52|73.86
    Reykjavík|64.15|-21.94
    Riga|56.95|24.11
    Rio de Janeiro|-22.91|-43.17
    Riyadh|24.71|46.68
    Rome|41.90|12.50
    San Francisco|37.77|-122.42
    Santiago|-33.45|-70.67
    São Paulo|-23.55|-46.63
    Seattle|47.61|-122.33
    Seoul|37.57|126.98
    Shanghai|31.23|121.47
    Singapore|1.35|103.82
    Sofia|42.70|23.32
    Stockholm|59.33|18.07
    Sydney|-33.87|151.21
    Taipei|25.03|121.57
    Tallinn|59.44|24.75
    Tashkent|41.30|69.24
    Tehran|35.69|51.39
    Tel Aviv|32.09|34.78
    Tokyo|35.68|139.69
    Toronto|43.65|-79.38
    Vancouver|49.28|-123.12
    Vienna|48.21|16.37
    Vilnius|54.69|25.28
    Warsaw|52.23|21.01
    Washington|38.91|-77.04
    Wellington|-41.29|174.78
    Zürich|47.38|8.54
""".trimIndent().lines().map { line ->
    val (name, lat, lon) = line.split('|')
    Place(name, lat.toDouble(), lon.toDouble())
}

/** Cities whose name starts with (or contains) [query], best matches first. */
fun findCities(query: String, limit: Int = 8): List<Place> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    val starts = CITIES.filter { it.name.startsWith(q, ignoreCase = true) }
    val contains = CITIES.filter { it.name.contains(q, ignoreCase = true) && it !in starts }
    return (starts + contains).take(limit)
}

/** A guess from the Windows time zone (e.g. Europe/Berlin -> Berlin), or null. */
fun guessCity(zone: ZoneId = ZoneId.systemDefault()): Place? {
    val city = zone.id.substringAfterLast('/').replace('_', ' ')
    val aliases = mapOf("Calcutta" to "Kolkata", "Kiev" to "Kyiv", "Saigon" to "Ho Chi Minh City", "Sao Paulo" to "São Paulo",
        "Bogota" to "Bogotá", "Zurich" to "Zürich", "Reykjavik" to "Reykjavík")
    val name = aliases[city] ?: city
    return CITIES.firstOrNull { it.name.equals(name, ignoreCase = true) }
}
