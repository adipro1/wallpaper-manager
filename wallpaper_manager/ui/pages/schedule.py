"""Schedule page: daily rules like 07:00 → morning.jpg or 18:00 → a playlist."""

import logging
from datetime import datetime
from tkinter import messagebox

import customtkinter as ctk

from ...database import ScheduleRule
from ...scheduler import parse_time
from .. import icons, theme
from ..chooser import MediaChooser
from ..widgets import apply_window_icon, BasePage, Card, PageHeader, button, icon_button, run_in_thread, section_label, segmented

log = logging.getLogger(__name__)

RULE_THUMB = (96, 54)
STATUS_REFRESH_MS = 30_000


def describe_wait(when: datetime, now: datetime) -> str:
    minutes = max(0, int((when - now).total_seconds() // 60))
    hours, minutes = divmod(minutes, 60)
    return f"in {hours} h {minutes} min" if hours else f"in {minutes} min"


class RuleDialog(ctk.CTkToplevel):
    """Add or edit a rule: a time plus either a wallpaper or a playlist."""

    def __init__(self, page: "SchedulePage", rule: ScheduleRule | None = None):
        super().__init__(page, fg_color=theme.WINDOW_BG)
        apply_window_icon(self)
        self.page = page
        self.rule = rule
        self.media_id = rule.media_id if rule else None
        self.playlists = {p.name: p.id for p in page.app.db.list_playlists()}
        self.title("Edit rule" if rule else "Add rule")
        self.geometry("860x640")
        self.minsize(600, 460)

        top = ctk.CTkFrame(self, fg_color="transparent")
        top.pack(fill="x", padx=20, pady=(18, 10))
        ctk.CTkLabel(top, text="Time", font=theme.font(14), text_color=theme.TEXT).pack(side="left")
        self.time = ctk.CTkEntry(top, width=80, height=34, font=theme.font(15), justify="center",
                                 placeholder_text="HH:MM", fg_color=theme.INPUT_BG, border_color=theme.CARD_BORDER,
                                 border_width=1)
        self.time.pack(side="left", padx=(10, 24))
        if rule:
            self.time.insert(0, rule.time)
        ctk.CTkLabel(top, text="Show", font=theme.font(14), text_color=theme.TEXT).pack(side="left")
        self.kind = segmented(top, ["Wallpaper", "Playlist"], lambda _v: self._show_kind(), height=34)
        self.kind.set("Playlist" if rule and rule.playlist_id else "Wallpaper")
        self.kind.pack(side="left", padx=10)
        self.playlist = ctk.CTkOptionMenu(top, values=list(self.playlists) or ["No playlists yet"], width=200,
                                          height=34, font=theme.font(13), fg_color=theme.SECONDARY_BTN,
                                          button_color=theme.SECONDARY_BTN,
                                          button_hover_color=theme.SECONDARY_BTN_HOVER, text_color=theme.TEXT)
        if rule and rule.playlist_id:
            self.playlist.set(next((n for n, i in self.playlists.items() if i == rule.playlist_id), ""))

        self.chooser = MediaChooser(self, page.app, self._pick, allow_video=True)
        self.error = ctk.CTkLabel(self, text="", font=theme.font(12), text_color=theme.DANGER, anchor="w")
        bottom = ctk.CTkFrame(self, fg_color="transparent")
        bottom.pack(side="bottom", fill="x", padx=20, pady=14)
        button(bottom, "Save", self._save, style="primary", width=110).pack(side="right")
        button(bottom, "Cancel", self.destroy, width=100).pack(side="right", padx=8)
        self.error.pack(side="bottom", fill="x", padx=22)
        self._show_kind()
        self.bind("<Escape>", lambda _e: self.destroy())
        self.after(150, lambda: (self.lift(), self.focus_force(), self.time.focus_set()))

    def _show_kind(self) -> None:
        if self.kind.get() == "Wallpaper":
            self.playlist.pack_forget()
            self.chooser.pack(fill="both", expand=True, padx=16)
            self.chooser.refresh()
            self.chooser.set_current(self.media_id)
        else:
            self.chooser.pack_forget()
            self.playlist.pack(side="left", padx=(6, 0))
            if not self.playlists:
                self.error.configure(text="Create a playlist on the Playlists page first.")

    def _pick(self, item) -> None:
        self.media_id = item.id
        self.chooser.set_current(item.id)
        self.error.configure(text="")

    def _save(self) -> None:
        try:
            time = parse_time(self.time.get())
        except ValueError as exc:
            self.error.configure(text=str(exc))
            return
        media_id = playlist_id = None
        if self.kind.get() == "Wallpaper":
            if not self.media_id:
                self.error.configure(text="Click a wallpaper to choose it.")
                return
            media_id = self.media_id
        else:
            playlist_id = self.playlists.get(self.playlist.get())
            if not playlist_id:
                self.error.configure(text="Choose a playlist.")
                return
        self.page.save_rule(self.rule, time, media_id, playlist_id)
        self.destroy()


class RuleRow(ctk.CTkFrame):
    def __init__(self, master, page: "SchedulePage", rule: ScheduleRule, active: bool):
        super().__init__(master, corner_radius=8, fg_color=theme.LIST_SELECTED if active else "transparent")
        self.grid_columnconfigure(2, weight=1)
        ctk.CTkLabel(self, text=rule.time, width=76, font=theme.font(20, "bold"),
                     text_color=theme.TEXT if rule.enabled else theme.TEXT_SUBTLE).grid(
            row=0, column=0, rowspan=2, padx=(10, 6))
        if rule.media_id:
            item = page.app.db.get_media(rule.media_id)
            image = page.app.thumbs.get(item, RULE_THUMB)
            kind = "Video (live wallpaper)" if item.is_video else "Image"
        else:
            image = icons.icon("playlist", 28, theme.ACCENT)
            kind = "Playlist"
        ctk.CTkLabel(self, text="", image=image, width=RULE_THUMB[0], height=RULE_THUMB[1]).grid(
            row=0, column=1, rowspan=2, padx=8, pady=6)
        ctk.CTkLabel(self, text=rule.target_name, font=theme.font(14), text_color=theme.TEXT, anchor="w").grid(
            row=0, column=2, sticky="sw")
        ctk.CTkLabel(self, text=kind + ("   ·  Active now" if active else ""), font=theme.font(11),
                     text_color=theme.ACCENT if active else theme.TEXT_MUTED, anchor="w").grid(
            row=1, column=2, sticky="nw")

        controls = ctk.CTkFrame(self, fg_color="transparent")
        controls.grid(row=0, column=3, rowspan=2, padx=8)
        switch = ctk.CTkSwitch(controls, text="", width=46, progress_color=theme.ACCENT,
                               command=lambda: page.toggle_rule(rule, bool(switch.get())))
        if rule.enabled:
            switch.select()
        switch.pack(side="left", padx=(0, 6))
        button(controls, "Apply now", lambda: page.apply_now(rule), icon="play", height=30).pack(side="left", padx=4)
        icon_button(controls, "edit", lambda: RuleDialog(page, rule)).pack(side="left")
        icon_button(controls, "delete", lambda: page.delete_rule(rule), color=theme.DANGER).pack(side="left")


class SchedulePage(BasePage):
    title = "Schedule"

    def __init__(self, master, app):
        super().__init__(master, app)
        self.scheduler = app.scheduler
        self._refresh_job = None
        self.grid_columnconfigure(0, weight=1)
        self.grid_rowconfigure(2, weight=1)

        header = PageHeader(self, "Schedule", "Change the wallpaper automatically at set times every day.")
        header.grid(row=0, column=0, sticky="ew", padx=32, pady=(28, 16))
        self.enabled = ctk.CTkSwitch(header.actions, text="Schedule on", font=theme.font(13),
                                     progress_color=theme.ACCENT, command=self._toggle_enabled)
        self.enabled.pack(side="left", padx=(0, 16))
        button(header.actions, "Add rule", lambda: RuleDialog(self), icon="add", style="primary").pack(side="left")

        status = Card(self)
        status.grid(row=1, column=0, sticky="ew", padx=32, pady=(0, 16))
        self.now_label = ctk.CTkLabel(status, text="", font=theme.font(14), text_color=theme.TEXT, anchor="w")
        self.now_label.pack(fill="x", padx=20, pady=(14, 0))
        self.next_label = ctk.CTkLabel(status, text="", font=theme.font(13), text_color=theme.TEXT_MUTED, anchor="w")
        self.next_label.pack(fill="x", padx=20, pady=(2, 14))

        rules_card = Card(self)
        rules_card.grid(row=2, column=0, sticky="nsew", padx=32, pady=(0, 20))
        section_label(rules_card, "Rules").pack(anchor="w", padx=18, pady=(14, 4))
        self.rules_frame = ctk.CTkScrollableFrame(rules_card, fg_color="transparent")
        self.rules_frame.pack(fill="both", expand=True, padx=8, pady=(0, 10))
        self.rules_frame.grid_columnconfigure(0, weight=1)

    def on_show(self) -> None:
        self.refresh()
        self._schedule_refresh()

    def on_hide(self) -> None:
        if self._refresh_job:
            self.after_cancel(self._refresh_job)
            self._refresh_job = None

    def _schedule_refresh(self) -> None:
        self._refresh_job = self.after(STATUS_REFRESH_MS, self._periodic_refresh)

    def _periodic_refresh(self) -> None:
        self.refresh()
        self._schedule_refresh()

    def refresh(self) -> None:
        if self.scheduler.enabled:
            self.enabled.select()
        else:
            self.enabled.deselect()
        rules = self.app.db.list_rules()
        active = self.scheduler.active()
        upcoming = self.scheduler.upcoming()
        now = self.scheduler.clock()

        if not self.scheduler.enabled:
            self.now_label.configure(text="The schedule is off.")
            self.next_label.configure(text="Turn it on to change wallpapers at the times below.")
        elif active is None:
            self.now_label.configure(text="No rules yet.")
            self.next_label.configure(text="Add a rule, e.g. 07:00 → a morning picture.")
        else:
            self.now_label.configure(text=f"Now:  {active[1].time} → {active[1].target_name}")
            when, rule = upcoming
            self.next_label.configure(text=f"Next:  {rule.time} → {rule.target_name}  ({describe_wait(when, now)})")

        for child in self.rules_frame.winfo_children():
            child.destroy()
        active_id = active[1].id if active and self.scheduler.enabled else None
        for index, rule in enumerate(rules):
            RuleRow(self.rules_frame, self, rule, rule.id == active_id).grid(row=index, column=0, sticky="ew", pady=1)
        if not rules:
            ctk.CTkLabel(self.rules_frame, text="No rules yet - use “Add rule”.", font=theme.font(13),
                         text_color=theme.TEXT_MUTED).grid(row=0, column=0, pady=40)

    # -- actions ------------------------------------------------------------------------

    def save_rule(self, rule: ScheduleRule | None, time: str, media_id: int | None, playlist_id: int | None) -> None:
        if rule:
            self.app.db.update_rule(rule.id, time, media_id, playlist_id)
        else:
            self.app.db.add_rule(time, media_id, playlist_id)
        self.scheduler.mark_current_applied()  # edits take effect at the next rule time
        self.refresh()

    def toggle_rule(self, rule: ScheduleRule, enabled: bool) -> None:
        self.app.db.set_rule_enabled(rule.id, enabled)
        self.scheduler.mark_current_applied()
        self.refresh()

    def delete_rule(self, rule: ScheduleRule) -> None:
        if messagebox.askyesno("Delete rule", f"Delete the rule {rule.time} → {rule.target_name}?", parent=self):
            self.app.db.delete_rule(rule.id)
            self.scheduler.mark_current_applied()
            self.refresh()

    def apply_now(self, rule: ScheduleRule) -> None:
        def failed(exc):
            messagebox.showerror("Could not apply the rule", str(exc), parent=self)

        run_in_thread(self, lambda: self.scheduler.apply_rule(rule), on_done=lambda _r: self.refresh(),
                      on_error=failed)

    def _toggle_enabled(self) -> None:
        self.scheduler.set_enabled(bool(self.enabled.get()))
        self.refresh()
