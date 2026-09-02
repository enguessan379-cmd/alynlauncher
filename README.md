# Crestwood SA-MP Mobile

Client/launcher pour SA-MP sur Android, avec interface overlay intégrée.

## ✨ Fonctionnalités

- Interface utilisateur overlay (ImGui)
- Support réseau via RakNet
- Système de logging (spdlog)
- Compatible arm64-v8a / armeabi-v7a

## 📱 Prérequis

- Android 7.0 (API 24) minimum
- Android 15 (API 35) recommandé

## 🛠️ Compilation

Le projet utilise Gradle + CMake/NDK pour la partie native (C++20).

```bash
git clone https://github.com/enguessan379-cmd/alynlauncher.git
cd alynlauncher
./gradlew assembleDebug
