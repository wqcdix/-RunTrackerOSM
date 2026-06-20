RunTrackerOSM — Android-приложение «Трекер пробежки»

Что реализовано:
1. Отображение карты OpenStreetMap через osmdroid.
2. Запрос разрешений на геолокацию.
3. Получение координат пользователя через Fused Location Provider.
4. Запись маршрута пробежки.
5. Отображение маршрута линией на карте.
6. Маркер старта и маркер финиша.
7. Расчет дистанции.
8. Расчет текущей скорости.
9. Расчет средней скорости.
10. Отображение высоты.
11. Расчет набора высоты.
12. Foreground Service для стабильной записи трека.

Как открыть:
1. Распакуй архив RunTrackerOSM.zip.
2. В Android Studio выбери File > Open.
3. Выбери папку RunTrackerOSM.
4. Дождись Gradle Sync.
5. Запусти приложение на реальном телефоне.

Важно:
- Google Maps API key не нужен.
- Для карты нужен интернет.
- Для полноценной проверки лучше использовать реальный телефон, а не эмулятор.
- При первом запуске разреши доступ к геолокации.

Основные файлы:
app/src/main/java/com/example/runtracker/MainActivity.kt
app/src/main/java/com/example/runtracker/TrackingService.kt
app/src/main/res/layout/activity_main.xml
app/src/main/AndroidManifest.xml
app/build.gradle.kts
