import CoreBluetooth
import UIKit

/// Protocolo para notificar eventos BLE a la UI.
protocol FlicBleManagerDelegate: AnyObject {
    func bleDidUpdateState(_ state: FlicBleManager.ConnectionState)
    func bleDidReceiveAlert(data: Data)
}

/// Manager central de BLE que conecta al reloj FLIC usando Core Bluetooth.
///
/// Usa Background Mode `bluetooth-central` + State Restoration para
/// mantener la conexion BLE activa en background. iOS despierta la app
/// cuando el reloj envia una notificacion (boton SOS presionado).
class FlicBleManager: NSObject {

    static let shared = FlicBleManager()

    enum ConnectionState: String {
        case disconnected = "Desconectado"
        case scanning = "Buscando..."
        case connecting = "Conectando..."
        case connected = "Conectado"
        case error = "Error"
    }

    weak var delegate: FlicBleManagerDelegate?

    private var centralManager: CBCentralManager!
    private var connectedPeripheral: CBPeripheral?
    private var notifyCharacteristic: CBCharacteristic?

    private let prefs = AppPreferences.shared
    private let apiClient = APIClient.shared

    private(set) var state: ConnectionState = .disconnected {
        didSet { delegate?.bleDidUpdateState(state) }
    }

    // UUIDs
    private let flicServiceUUID = CBUUID(string: Config.flicServiceUUID)
    private let flicNotifyUUID = CBUUID(string: Config.flicNotifyUUID)

    // Cooldown entre alertas (30 segundos)
    private var lastAlertTime: Date?
    private let alertCooldown: TimeInterval = 30

    // Reconexion
    private var reconnectTimer: Timer?

    // MARK: - State Restoration Key
    private static let restorationKey = "com.siemprecerca.monitor.ble"

    override init() {
        super.init()
        // State Restoration: iOS reconstruye el CBCentralManager si la app fue terminada
        centralManager = CBCentralManager(
            delegate: self,
            queue: nil,
            options: [CBCentralManagerOptionRestoreIdentifierKey: FlicBleManager.restorationKey]
        )
    }

    // MARK: - Public

    func startMonitoring() {
        guard centralManager.state == .poweredOn else {
            print("[BLE] Bluetooth no esta encendido")
            return
        }

        if let mac = prefs.deviceMAC, !mac.isEmpty {
            // Intentar reconectar a un periferico conocido
            let knownPeripherals = centralManager.retrievePeripherals(withIdentifiers: [])
            // En iOS no podemos conectar por MAC directamente, escaneamos
            startScan()
        } else {
            startScan()
        }
    }

    func stopMonitoring() {
        stopScan()
        disconnect()
    }

    func startScan() {
        guard centralManager.state == .poweredOn else { return }
        state = .scanning

        centralManager.scanForPeripherals(
            withServices: nil, // Escanear todo para encontrar FLIC
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
        )

        print("[BLE] Escaneo iniciado")

        // Timeout de 30 segundos
        DispatchQueue.main.asyncAfter(deadline: .now() + 30) { [weak self] in
            guard let self = self, self.state == .scanning else { return }
            self.stopScan()
            self.scheduleReconnect()
        }
    }

    func stopScan() {
        centralManager.stopScan()
        if state == .scanning {
            state = .disconnected
        }
    }

    // MARK: - Private

    private func disconnect() {
        reconnectTimer?.invalidate()
        if let peripheral = connectedPeripheral {
            centralManager.cancelPeripheralConnection(peripheral)
        }
        connectedPeripheral = nil
        notifyCharacteristic = nil
        state = .disconnected
    }

    private func scheduleReconnect() {
        reconnectTimer?.invalidate()
        reconnectTimer = Timer.scheduledTimer(withTimeInterval: 10, repeats: false) { [weak self] _ in
            self?.startMonitoring()
        }
        print("[BLE] Reconexion programada en 10s")
    }

    private func processAlert(data: Data) {
        // Cooldown
        if let last = lastAlertTime, Date().timeIntervalSince(last) < alertCooldown {
            print("[BLE] Alerta ignorada por cooldown")
            return
        }
        lastAlertTime = Date()

        prefs.alertCount += 1
        prefs.lastAlertTime = Date()

        let hex = data.map { String(format: "%02X", $0) }.joined(separator: " ")
        print("[BLE] ALERTA SOS: \(hex)")

        // Vibrar
        let generator = UINotificationFeedbackGenerator()
        generator.notificationOccurred(.warning)

        // Notificacion local
        showLocalNotification()

        // Enviar al servidor
        let serial = prefs.deviceSerial ?? ""
        apiClient.sendAlert(deviceSerial: serial, bleData: data)

        // Notificar a la UI
        delegate?.bleDidReceiveAlert(data: data)
    }

    private func showLocalNotification() {
        let content = UNMutableNotificationContent()
        content.title = "ALERTA SOS"
        content.body = "\(prefs.clientName) presiono el boton de emergencia"
        content.sound = .defaultCritical
        content.categoryIdentifier = "SOS_ALERT"

        let request = UNNotificationRequest(
            identifier: UUID().uuidString,
            content: content,
            trigger: nil // Inmediato
        )

        UNUserNotificationCenter.current().add(request)
    }
}

// MARK: - CBCentralManagerDelegate

extension FlicBleManager: CBCentralManagerDelegate {

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            print("[BLE] Bluetooth encendido")
            if prefs.isSetupComplete {
                startMonitoring()
            }
        case .poweredOff:
            print("[BLE] Bluetooth apagado")
            state = .disconnected
        default:
            break
        }
    }

    /// State Restoration: iOS reconstruye los perifericos conectados
    func centralManager(_ central: CBCentralManager,
                        willRestoreState dict: [String: Any]) {
        if let peripherals = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral],
           let peripheral = peripherals.first {
            print("[BLE] State Restoration: reconectando a \(peripheral.name ?? "?")")
            connectedPeripheral = peripheral
            peripheral.delegate = self
            state = .connected
        }
    }

    func centralManager(_ central: CBCentralManager,
                        didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any],
                        rssi RSSI: NSNumber) {
        guard let name = peripheral.name, name.hasPrefix(Config.flicDevicePrefix) else { return }

        // Si tenemos un serial configurado, buscar ese especifico
        if let expectedSerial = prefs.deviceSerial {
            guard name.contains(expectedSerial) else { return }
        }

        print("[BLE] Encontrado: \(name)")
        stopScan()

        connectedPeripheral = peripheral
        peripheral.delegate = self
        state = .connecting
        central.connect(peripheral, options: [
            CBConnectPeripheralOptionNotifyOnDisconnectionKey: true
        ])
    }

    func centralManager(_ central: CBCentralManager,
                        didConnect peripheral: CBPeripheral) {
        print("[BLE] Conectado a \(peripheral.name ?? "?")")
        state = .connected

        // Guardar el identificador para reconexion
        if prefs.deviceMAC == nil || prefs.deviceMAC!.isEmpty {
            prefs.deviceMAC = peripheral.identifier.uuidString
        }

        peripheral.discoverServices([flicServiceUUID])
    }

    func centralManager(_ central: CBCentralManager,
                        didDisconnectPeripheral peripheral: CBPeripheral,
                        error: Error?) {
        print("[BLE] Desconectado: \(error?.localizedDescription ?? "sin error")")
        connectedPeripheral = nil
        notifyCharacteristic = nil
        state = .disconnected

        // Reconectar automaticamente
        scheduleReconnect()
    }

    func centralManager(_ central: CBCentralManager,
                        didFailToConnect peripheral: CBPeripheral,
                        error: Error?) {
        print("[BLE] Fallo al conectar: \(error?.localizedDescription ?? "")")
        state = .error
        scheduleReconnect()
    }
}

// MARK: - CBPeripheralDelegate

extension FlicBleManager: CBPeripheralDelegate {

    func peripheral(_ peripheral: CBPeripheral,
                    didDiscoverServices error: Error?) {
        guard let services = peripheral.services else { return }

        for service in services {
            if service.uuid == flicServiceUUID {
                peripheral.discoverCharacteristics([flicNotifyUUID], for: service)
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didDiscoverCharacteristicsFor service: CBService,
                    error: Error?) {
        guard let characteristics = service.characteristics else { return }

        for char in characteristics {
            if char.uuid == flicNotifyUUID {
                notifyCharacteristic = char
                peripheral.setNotifyValue(true, for: char)
                print("[BLE] Notificaciones activadas en \(char.uuid)")
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didUpdateValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard characteristic.uuid == flicNotifyUUID,
              let data = characteristic.value else { return }

        processAlert(data: data)
    }
}
