#!/system/bin/sh
echo "== pa_fix v2: component toggle -> PACKAGE_CHANGED -> provider re-register =="
pm disable com.xiaomi.misettings/com.xiaomi.misettings.usagestats.widget.desktop.widget.SimpleUsageStatsWidget
sleep 1
pm enable com.xiaomi.misettings/com.xiaomi.misettings.usagestats.widget.desktop.widget.SimpleUsageStatsWidget
echo "--- misettings done"
pm disable com.miui.notes/com.miui.notes.widget.NoteWidgetProvider_4x2
sleep 1
pm enable com.miui.notes/com.miui.notes.widget.NoteWidgetProvider_4x2
echo "--- notes done"
echo "== done =="
