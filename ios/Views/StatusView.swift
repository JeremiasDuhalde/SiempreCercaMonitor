import SwiftUI

/// Pantalla principal que muestra el estado del monitoreo.
/// UI minima — solo informacion de estado.
struct StatusView: View {

    @State private var connectionState: FlicBleManager.ConnectionState = .disconnected
    @State private var alertCount: Int = 0
    @State private var lastAlert: Date?

    private let prefs = AppPreferences.shared
    private let bleManager = FlicBleManager.shared
    private let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "dd/MM/yyyy HH:mm:ss"
        return f
    }()

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {

                // Header
                VStack(spacing: 4) {
                    Text("SiempreCerca")
                        .font(.system(size: 28, weight: .bold))
                        .foregroundColor(Color(hex: "E94560"))
                    Text("Monitor BLE")
                        .font(.subheadline)
                        .foregroundColor(.gray)
                }
                .padding(.top, 20)

                // Status Card
                HStack {
                    Circle()
                        .fill(connectionState == .connected ? Color.green : Color.red)
                        .frame(width: 16, height: 16)
                    Text(connectionState.rawValue)
                        .font(.title2)
                        .fontWeight(.bold)
                }
                .frame(maxWidth: .infinity)
                .padding()
                .background(Color(UIColor.secondarySystemBackground))
                .cornerRadius(12)

                // Info Card
                VStack(alignment: .leading, spacing: 10) {
                    infoRow(label: "Reloj", value: prefs.deviceSerial ?? "No configurado")
                    infoRow(label: "Paciente", value: prefs.clientName)
                    infoRow(label: "Bluetooth",
                            value: connectionState == .connected ? "Conectado" : "Desconectado")
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()
                .background(Color(UIColor.secondarySystemBackground))
                .cornerRadius(12)

                // Alerts Card
                VStack(alignment: .leading, spacing: 10) {
                    Text("Alertas")
                        .font(.headline)
                        .foregroundColor(Color(hex: "E94560"))

                    infoRow(label: "Ultima alerta",
                            value: lastAlert != nil ? dateFormatter.string(from: lastAlert!) : "Ninguna")
                    infoRow(label: "Total alertas", value: "\(alertCount)")
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()
                .background(Color(UIColor.secondarySystemBackground))
                .cornerRadius(12)

                // Contacts Card
                VStack(alignment: .leading, spacing: 8) {
                    let contacts = prefs.contacts
                    if contacts.isEmpty {
                        Text("Contactos SMS: No configurados")
                            .foregroundColor(.gray)
                    } else {
                        Text("Contactos de emergencia")
                            .font(.headline)
                            .foregroundColor(Color(hex: "E94560"))
                        ForEach(contacts, id: \.id) { contact in
                            Text("\(contact.name) — \(contact.phone)")
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()
                .background(Color(UIColor.secondarySystemBackground))
                .cornerRadius(12)

                // Buttons
                Button("Reconectar") {
                    bleManager.startMonitoring()
                }
                .buttonStyle(.borderedProminent)
                .tint(Color(hex: "E94560"))
                .frame(maxWidth: .infinity)

                Button("Reconfigurar") {
                    prefs.isSetupComplete = false
                    // Force app restart
                }
                .buttonStyle(.bordered)
                .foregroundColor(.gray)
                .frame(maxWidth: .infinity)

                Text("v1.0.0")
                    .font(.caption2)
                    .foregroundColor(.gray)
                    .padding(.top, 8)
            }
            .padding(24)
        }
        .onAppear {
            alertCount = prefs.alertCount
            lastAlert = prefs.lastAlertTime
            bleManager.delegate = StatusViewBLEDelegate(
                onStateChange: { state in
                    connectionState = state
                },
                onAlert: { _ in
                    alertCount = prefs.alertCount
                    lastAlert = prefs.lastAlertTime
                }
            )
            bleManager.startMonitoring()
        }
    }

    private func infoRow(label: String, value: String) -> some View {
        HStack {
            Text(label)
                .foregroundColor(.gray)
            Spacer()
            Text(value)
                .fontWeight(.medium)
        }
    }
}

/// Delegate wrapper para conectar BLE events con SwiftUI
class StatusViewBLEDelegate: FlicBleManagerDelegate {
    let onStateChange: (FlicBleManager.ConnectionState) -> Void
    let onAlert: (Data) -> Void

    init(onStateChange: @escaping (FlicBleManager.ConnectionState) -> Void,
         onAlert: @escaping (Data) -> Void) {
        self.onStateChange = onStateChange
        self.onAlert = onAlert
    }

    func bleDidUpdateState(_ state: FlicBleManager.ConnectionState) {
        DispatchQueue.main.async { self.onStateChange(state) }
    }

    func bleDidReceiveAlert(data: Data) {
        DispatchQueue.main.async { self.onAlert(data) }
    }
}
