from ..widgets import PlaceholderPage


class SchedulePage(PlaceholderPage):
    title = "Schedule"
    icon_name = "schedule"
    subtitle = "Change wallpapers automatically at set times."
    planned = ["Time-based rules, e.g. 07:00 → morning.jpg", "Schedules persist after restarting the app"]
