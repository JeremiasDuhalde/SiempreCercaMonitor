# SiempreCerca Monitor - iOS

App iOS nativa (SwiftUI + Core Bluetooth) que conecta directamente con relojes FLIC via BLE.

## Que hace

1. Se conecta al reloj FLIC por BLE usando Core Bluetooth
2. Usa **Background Mode `bluetooth-central`** para mantener la conexion en background
3. Usa **State Restoration** para sobrevivir si iOS termina la app
4. Escucha el boton SOS (Notify UUID `00420002`)
5. Envia alerta HTTP al servidor SiempreCerca
6. Muestra notificacion local de emergencia

## Requisitos

- iOS 15.0+
- iPhone con Bluetooth Low Energy
- Xcode 15+ (para compilar)

## Setup del proyecto en Xcode

1. Abrir Xcode > File > New > Project > App (SwiftUI)
2. Product Name: `SiempreCercaMonitor`
3. Bundle Identifier: `com.siemprecerca.monitor`
4. Copiar todos los archivos .swift de este directorio al proyecto
5. En Signing & Capabilities:
   - Agregar "Background Modes" > activar "Uses Bluetooth LE accessories"
6. Reemplazar el Info.plist con el de este directorio
7. Build & Run

## Distribucion sin App Store

Para instalar en iPhones de usuarios sin publicar en el App Store:

### Opcion A: TestFlight (recomendada)
- Requiere cuenta Apple Developer ($99/año)
- Subir el build a App Store Connect > TestFlight
- Invitar a los usuarios por email
- Ellos instalan TestFlight y luego la app
- Valido por 90 dias, renovable

### Opcion B: Ad Hoc
- Registrar los UDIDs de cada iPhone
- Generar un provisioning profile Ad Hoc
- Distribuir el .ipa directamente
- Maximo 100 dispositivos

### Opcion C: Enterprise (para empresas)
- Requiere Apple Developer Enterprise Program ($299/año)
- Distribucion libre sin limite de dispositivos
- No requiere registrar UDIDs

## Diferencias con la version Android

| Caracteristica | Android | iOS |
|---|---|---|
| BLE background | ForegroundService (24/7) | Background Mode bluetooth-central |
| Auto-start al boot | Si (BootReceiver) | No — abrir la app 1 vez despues de reiniciar |
| SMS automatico | Si (SEND_SMS permission) | No — el servidor envia SMS/WhatsApp |
| Distribucion | APK directo | TestFlight / Ad Hoc |
| Fiabilidad BLE | Excelente | Muy buena con State Restoration |
