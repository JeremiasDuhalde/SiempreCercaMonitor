import Foundation

/// Configuracion precargada de fabrica.
/// Estos valores vienen incorporados en la app para que el usuario
/// no-tecnico no tenga que ingresar datos del servidor.
enum Config {
    static let baseURL = "https://app.siemprecercasrl.net"
    static let webhookSecret = "4d60192711902e66a26923bfc375cb73"
    static let loginEmail = "monitor@siemprecerca.app"
    static let loginPassword = "M0n1t0r$SC2026!"

    static let smsTemplate = "ALERTA SOS - {nombre} necesita asistencia urgente. Contactar central Siempre Cerca: 2246-529000"

    static let flicDevicePrefix = "Flic"

    // UUIDs BLE del reloj FLIC
    static let flicServiceUUID = "00420000-8F59-4420-870D-84F3-B617E493"
    static let flicNotifyUUID = "00420002-8F59-4420-870D-84F3-B617E493"
    static let flicWriteUUID = "00420001-8F59-4420-870D-84F3-B617E493"
}
