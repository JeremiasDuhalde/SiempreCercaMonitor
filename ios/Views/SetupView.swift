import SwiftUI

/// Pantalla de configuracion simplificada.
/// El usuario solo necesita ingresar el numero de paciente y escanear el reloj.
struct SetupView: View {

    @State private var clientIdText = ""
    @State private var clientName: String?
    @State private var serverStatus = "Conectando..."
    @State private var serverConnected = false
    @State private var isSearchingClient = false
    @State private var isScanning = false
    @State private var foundDevices: [(name: String, id: String)] = []
    @State private var selectedDevice: (name: String, id: String)?
    @State private var showError = false
    @State private var errorMessage = ""
    @State private var setupComplete = false

    private let bleManager = FlicBleManager.shared

    var body: some View {
        if setupComplete {
            StatusView()
        } else {
            setupContent
        }
    }

    private var setupContent: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {

                // Header
                VStack(alignment: .leading, spacing: 4) {
                    Text("SiempreCerca")
                        .font(.system(size: 28, weight: .bold))
                        .foregroundColor(Color(hex: "E94560"))
                    Text("Configurar monitor")
                        .font(.subheadline)
                        .foregroundColor(.gray)
                }

                // Server status
                HStack {
                    Circle()
                        .fill(serverConnected ? Color.green : Color.orange)
                        .frame(width: 8, height: 8)
                    Text(serverStatus)
                        .font(.caption)
                        .foregroundColor(serverConnected ? .green : .orange)
                }

                // PASO 1: Paciente
                VStack(alignment: .leading, spacing: 12) {
                    Label("PASO 1 — PACIENTE", systemImage: "person.fill")
                        .font(.caption)
                        .fontWeight(.bold)
                        .foregroundColor(Color(hex: "E94560"))

                    Text("Ingresa el numero de paciente que te fue asignado")
                        .font(.caption)
                        .foregroundColor(.gray)

                    HStack {
                        TextField("Ej: 10", text: $clientIdText)
                            .keyboardType(.numberPad)
                            .textFieldStyle(.roundedBorder)
                            .frame(maxWidth: .infinity)

                        Button("Buscar") {
                            buscarPaciente()
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(Color(hex: "333355"))
                        .disabled(isSearchingClient)
                    }

                    if let name = clientName {
                        HStack {
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundColor(.green)
                            Text("Paciente: \(name)")
                                .foregroundColor(.green)
                        }
                    }
                }

                // PASO 2: Reloj
                VStack(alignment: .leading, spacing: 12) {
                    Label("PASO 2 — RELOJ", systemImage: "applewatch")
                        .font(.caption)
                        .fontWeight(.bold)
                        .foregroundColor(Color(hex: "E94560"))

                    Text("Asegurate que el reloj este cerca y con Bluetooth encendido")
                        .font(.caption)
                        .foregroundColor(.gray)

                    Button(isScanning ? "Detener busqueda" : "Buscar reloj") {
                        if isScanning {
                            bleManager.stopScan()
                            isScanning = false
                        } else {
                            isScanning = true
                            foundDevices.removeAll()
                            bleManager.startScan()
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Color(hex: "333355"))
                    .frame(maxWidth: .infinity)

                    if isScanning {
                        ProgressView()
                            .frame(maxWidth: .infinity)
                    }

                    ForEach(foundDevices, id: \.id) { device in
                        Button {
                            selectedDevice = device
                            bleManager.stopScan()
                            isScanning = false
                        } label: {
                            HStack {
                                Image(systemName: selectedDevice?.id == device.id
                                      ? "checkmark.circle.fill" : "circle")
                                    .foregroundColor(selectedDevice?.id == device.id ? .green : .gray)
                                Text(device.name)
                                    .foregroundColor(.primary)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding()
                            .background(Color(UIColor.secondarySystemBackground))
                            .cornerRadius(8)
                        }
                    }

                    if let device = selectedDevice {
                        HStack {
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundColor(.green)
                            Text("Reloj seleccionado: \(device.name)")
                                .foregroundColor(.green)
                        }
                    }
                }

                // Activar
                Button {
                    activarMonitoreo()
                } label: {
                    Text("Activar monitoreo")
                        .font(.headline)
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(Color(hex: "E94560"))
                        .cornerRadius(12)
                }
                .padding(.top, 8)
            }
            .padding(24)
        }
        .background(Color(UIColor.systemBackground))
        .onAppear { connectToServer() }
        .alert("Error", isPresented: $showError) {
            Button("OK") {}
        } message: {
            Text(errorMessage)
        }
    }

    // MARK: - Actions

    private func connectToServer() {
        APIClient.shared.login { success in
            DispatchQueue.main.async {
                serverConnected = success
                serverStatus = success ? "Servidor conectado" : "Error al conectar"
            }
        }
    }

    private func buscarPaciente() {
        guard let id = Int(clientIdText), id > 0 else {
            errorMessage = "Ingresa un numero de paciente valido"
            showError = true
            return
        }

        isSearchingClient = true
        APIClient.shared.fetchClient(id: id) { name in
            DispatchQueue.main.async {
                isSearchingClient = false
                if let name = name {
                    clientName = name
                } else {
                    errorMessage = "Paciente no encontrado. Verifica el numero."
                    showError = true
                }
            }
        }
    }

    private func activarMonitoreo() {
        guard let id = Int(clientIdText), id > 0, let name = clientName else {
            errorMessage = "Primero busca el paciente"
            showError = true
            return
        }
        guard let device = selectedDevice else {
            errorMessage = "Escanea y selecciona un reloj"
            showError = true
            return
        }

        let prefs = AppPreferences.shared
        prefs.clientId = id
        prefs.clientName = name
        prefs.deviceSerial = device.name.replacingOccurrences(of: "Flic ", with: "")
        prefs.deviceMAC = device.id
        prefs.isSetupComplete = true

        // Sincronizar contactos
        APIClient.shared.syncContacts()

        // Iniciar BLE
        bleManager.startMonitoring()

        setupComplete = true
    }
}

// MARK: - Color hex extension

extension Color {
    init(hex: String) {
        let hex = hex.trimmingCharacters(in: CharacterSet.alphanumerics.inverted)
        var int: UInt64 = 0
        Scanner(string: hex).scanHexInt64(&int)
        let r = Double((int >> 16) & 0xFF) / 255.0
        let g = Double((int >> 8) & 0xFF) / 255.0
        let b = Double(int & 0xFF) / 255.0
        self.init(red: r, green: g, blue: b)
    }
}
