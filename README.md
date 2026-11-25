# Tilt Runner

Endlos-Kugelspiel für Android, gesteuert per Neigung und Ruck (Kotlin, Jetpack Compose).

## Steuerung
- **Links/rechts neigen**: lenken
- **Nach vorn neigen** (Oberkante vom Körper weg): schneller, **zurück**: langsamer
- **Ruck nach oben**: springen (Tippen auf den Bildschirm springt ebenfalls, zum Testen)
- Beim Start gilt die aktuelle Haltung als „geradeaus".
- Die Sprung-Empfindlichkeit lässt sich im Menü per Schieberegler einstellen.

## Aufbau
- `game/GameEngine.kt`: Spiellogik ohne Android-Abhängigkeiten (Strecke, Lücken, Physik), mit Unit-Tests
- `sensor/MotionInput.kt`: Neigung (Schwerkraftsensor) und Ruck (lineare Beschleunigung, auf „oben" projiziert)
- `ui/GameScreen.kt`: Spielschleife, Menü und Pseudo-3D-Zeichnung per Canvas
- `data/Settings.kt`: Highscore und Empfindlichkeit (DataStore)

## Bauen
In Android Studio öffnen und auf einem echten Gerät starten (Sensoren!).
Unit-Tests der Spiellogik: `gradle :app:testDebugUnitTest` (Android Studio: Rechtsklick auf `GameEngineTest`).
