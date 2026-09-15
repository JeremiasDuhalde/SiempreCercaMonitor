# Guia para Play Console — SiempreCerca Monitor

Referencia rapida con las respuestas para los formularios de Google Play Console.

---

## 1. Justificacion de permisos sensibles

Google pide justificacion para cada permiso sensible. Copiar/pegar en la consola.

### SEND_SMS

> La app envia SMS de emergencia automaticos a contactos pre-configurados cuando se detecta una alerta SOS desde un boton FLIC vinculado. Esta funcion es critica para la seguridad de adultos mayores: el SMS se envia sin intervencion del usuario porque la persona que activa la alerta puede no estar en condiciones de operar el telefono. Los SMS solo se envian a numeros configurados previamente por el cuidador, no a numeros arbitrarios. La app NO usa SMS para marketing, verificacion ni ningun otro fin.

### ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION

> La app solicita permisos de ubicacion EXCLUSIVAMENTE porque Android lo requiere para realizar escaneo Bluetooth Low Energy (BLE) en versiones Android 6 a 11. La app NO recopila, almacena ni transmite datos de ubicacion. El permiso se usa unicamente para descubrir y conectar dispositivos FLIC BLE cercanos. En Android 12+ se usan los permisos BLUETOOTH_SCAN y BLUETOOTH_CONNECT sin necesidad de ubicacion.

### FOREGROUND_SERVICE (connectedDevice)

> La app mantiene un servicio en primer plano de tipo connectedDevice para mantener activa la conexion Bluetooth Low Energy con el reloj FLIC las 24/7. Esto es necesario porque la app funciona como sistema de monitoreo de emergencia para adultos mayores: debe detectar alertas SOS en tiempo real incluso con la pantalla apagada. Si el servicio se detuviera, las alertas de emergencia no se recibirian, poniendo en riesgo la seguridad del usuario.

### REQUEST_INSTALL_PACKAGES

> La app se distribuye fuera de Play Store a dispositivos dedicados de monitoreo. Incluye un mecanismo de auto-actualizacion que descarga APKs firmados desde el servidor de SiempreCerca (HTTPS). NOTA: Si Google objeta este permiso, se puede eliminar y deshabilitar la auto-actualizacion para la version de Play Store.

---

## 2. Data Safety Form — Respuestas

### Recopilacion de datos

| Tipo de dato | Se recopila? | Se comparte? | Proposito |
|---|---|---|---|
| Ubicacion | NO | NO | El permiso es solo para BLE scan, no se recopila ubicacion |
| Informacion personal | NO | NO | - |
| Contactos | NO | NO | Los contactos de emergencia se almacenan solo local |
| Numeros de telefono | SI (local) | NO | Contactos de emergencia para SMS, almacenados en SharedPreferences |
| Identificadores de dispositivo | SI | SI (servidor propio) | MAC BLE del FLIC, enviada al servidor de SiempreCerca para registro |
| Registros de app | SI | SI (servidor propio) | Alertas SOS enviadas al servidor para seguimiento |

### Practicas de seguridad

- **Datos cifrados en transito:** SI (HTTPS)
- **Mecanismo para solicitar eliminacion de datos:** SI (desinstalar app elimina datos locales; contactar a botondeayuda@siemprecercasrl.com para datos del servidor)
- **Dirigida a ninos:** NO

### Respuestas clave del formulario

- **La app comparte datos con terceros?** NO — el servidor es operado por SiempreCerca SRL, la misma empresa.
- **La app recopila datos de ubicacion?** NO — el permiso de ubicacion se usa solo para escaneo BLE, no se almacena ni transmite ubicacion.
- **Retencion de datos:** Los datos locales se eliminan con la desinstalacion. Los registros del servidor se retienen segun la politica de SiempreCerca.

---

## 3. Clasificacion de contenido (IARC)

Respuestas para el cuestionario:

- Violencia: NO
- Sexualidad: NO
- Lenguaje: NO
- Sustancias controladas: NO
- Contenido generado por usuario: NO
- Compras in-app: NO
- Publicidad: NO
- La app comparte ubicacion del usuario: NO

Clasificacion esperada: **Todos (Everyone)**

---

## 4. Checklist antes de subir

- [ ] Cuenta de desarrollador en Google Play Console (USD 25)
- [ ] Subir el repo a GitHub y activar GitHub Pages en la rama `main`, carpeta `/docs`
  - URL resultante: `https://<usuario>.github.io/SiempreCercaMonitor/privacy-policy.html`
- [ ] Generar AAB: `./gradlew bundleRelease`
- [ ] Icono 512x512 PNG (hi-res icon)
- [ ] Feature graphic 1024x500 PNG
- [ ] Minimo 2 screenshots de celular (recomendado 4-8)
- [ ] Completar ficha con textos de PLAY_STORE_LISTING.md
- [ ] Completar Data Safety form con respuestas de arriba
- [ ] Completar clasificacion IARC
- [ ] Justificar permisos sensibles con textos de arriba
- [ ] Decidir si eliminar REQUEST_INSTALL_PACKAGES para la version Play Store
- [ ] Publicar en test interno primero, luego produccion
