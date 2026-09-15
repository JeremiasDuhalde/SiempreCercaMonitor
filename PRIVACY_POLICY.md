# Politica de Privacidad — SiempreCerca Monitor

**Ultima actualizacion:** 4 de septiembre de 2026

SiempreCerca SRL ("nosotros", "nuestra empresa") opera la aplicacion movil SiempreCerca Monitor ("la App"). Esta politica de privacidad describe como recopilamos, usamos y protegemos la informacion cuando utilizas nuestra App.

## 1. Informacion que recopilamos

### Informacion del dispositivo
- Identificadores Bluetooth del reloj FLIC vinculado (direccion MAC BLE).
- Estado de conexion Bluetooth.

### Informacion de uso
- Registro de alertas SOS activadas (fecha, hora, estado de envio).
- Estado del servicio de monitoreo.

### Informacion de contacto
- Numeros de telefono de contactos de emergencia configurados por el usuario dentro de la App.

### Informacion de red
- Comunicacion con el servidor de SiempreCerca (https://app.siemprecercasrl.net) para enviar alertas y verificar actualizaciones.

## 2. Como usamos la informacion

Utilizamos la informacion recopilada exclusivamente para:

- **Monitoreo BLE:** Mantener la conexion Bluetooth con el reloj FLIC y detectar alertas SOS.
- **Envio de alertas:** Notificar al servidor de SiempreCerca y enviar SMS de emergencia a los contactos configurados cuando se activa una alerta SOS.
- **Funcionamiento continuo:** Asegurar que el servicio de monitoreo permanezca activo las 24 horas.
- **Actualizaciones:** Verificar y descargar actualizaciones de la App desde nuestro servidor.

## 3. Permisos de la App

La App solicita los siguientes permisos del sistema:

| Permiso | Proposito |
|---------|-----------|
| Bluetooth (BLE) | Conectar y comunicarse con el reloj FLIC |
| Ubicacion | Requerido por Android para escaneo BLE (no rastreamos ubicacion) |
| SMS | Enviar mensajes de emergencia a contactos configurados |
| Internet | Comunicacion con el servidor de SiempreCerca |
| Servicio en primer plano | Mantener el monitoreo activo en segundo plano |
| Inicio con el dispositivo | Iniciar el monitoreo automaticamente al encender el celular |
| Notificaciones | Mostrar el estado del servicio y alertas |

**Nota sobre ubicacion:** La App solicita permiso de ubicacion unicamente porque Android lo requiere para realizar escaneo Bluetooth Low Energy. No recopilamos, almacenamos ni transmitimos datos de ubicacion.

## 4. Comparticion de datos

- **No vendemos** informacion personal a terceros.
- **No compartimos** informacion con terceros, excepto el envio de alertas al servidor de SiempreCerca SRL, que es operado por nuestra propia empresa.
- Los SMS de emergencia se envian directamente desde el dispositivo a los numeros configurados por el usuario.

## 5. Almacenamiento y seguridad

- La informacion se almacena localmente en el dispositivo del usuario (SharedPreferences).
- Las comunicaciones con el servidor se realizan mediante HTTPS (conexion cifrada).
- No almacenamos datos personales en servidores externos mas alla del registro de alertas.

## 6. Retencion de datos

- Los datos locales se eliminan al desinstalar la App.
- Los registros de alertas en el servidor se retienen segun la politica de retencion del servicio de SiempreCerca.

## 7. Derechos del usuario

Podes:
- Eliminar tus datos locales desinstalando la App.
- Modificar o eliminar los contactos de emergencia en cualquier momento desde la App.
- Solicitar la eliminacion de registros de alertas del servidor contactandonos.

## 8. Cambios a esta politica

Podemos actualizar esta politica periodicamente. Notificaremos los cambios publicando la nueva version en esta pagina y actualizando la fecha de "ultima actualizacion".

## 9. Contacto

Si tenes preguntas sobre esta politica de privacidad, contactanos:

- **Empresa:** SiempreCerca SRL
- **Email:** botondeayuda@siemprecercasrl.com
- **Sitio web:** https://app.siemprecercasrl.net
