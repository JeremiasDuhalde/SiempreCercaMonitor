# SiempreCerca Monitor

App Android nativa que conecta directamente con relojes FLIC via Bluetooth Low Energy (BLE), eliminando la dependencia de la app FLIC oficial.

## Que hace

1. Se conecta al reloj FLIC por BLE
2. Escucha el boton SOS (caracteristica Notify `00420002`)
3. Envia alerta HTTP al servidor SiempreCerca
4. Envia SMS a los contactos de emergencia del paciente
5. Corre 24/7 como ForegroundService con reconexion automatica

## Requisitos

- Android 8.0+ (API 26)
- Bluetooth Low Energy
- Permiso SMS (para alertas por SMS)

## Compilar

```bash
# Debug APK
./gradlew assembleDebug

# Release APK (requiere keystore)
./gradlew assembleRelease
```

El APK queda en `app/build/outputs/apk/debug/app-debug.apk`

## Instalar

1. Copiar el APK al celular Android
2. Abrir el APK > "Permitir fuentes desconocidas" > Instalar
3. Abrir la app > Configurar servidor + escanear reloj
4. Listo, corre sola

## Arquitectura

- `FlicBleService` — ForegroundService con conexion BLE persistente
- `AlertManager` — Envio de alertas HTTP + SMS
- `MonitorWorker` — WorkManager periodico (cada 15 min) que revive el servicio
- `BootReceiver` — Arranca el servicio al encender el celular
- `BluetoothStateReceiver` — Reconecta si Bluetooth se apaga y se vuelve a encender
