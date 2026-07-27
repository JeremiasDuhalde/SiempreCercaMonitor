import Foundation

/// Persistencia local usando UserDefaults.
/// Guarda la configuracion del dispositivo y datos del paciente.
class AppPreferences {

    static let shared = AppPreferences()
    private let defaults = UserDefaults.standard

    private enum Keys {
        static let setupComplete = "setup_complete"
        static let deviceSerial = "device_serial"
        static let deviceMAC = "device_mac"
        static let clientId = "client_id"
        static let clientName = "client_name"
        static let authToken = "auth_token"
        static let alertCount = "alert_count"
        static let lastAlertTime = "last_alert_time"
        static let contacts = "emergency_contacts"
    }

    var isSetupComplete: Bool {
        get { defaults.bool(forKey: Keys.setupComplete) }
        set { defaults.set(newValue, forKey: Keys.setupComplete) }
    }

    var deviceSerial: String? {
        get { defaults.string(forKey: Keys.deviceSerial) }
        set { defaults.set(newValue, forKey: Keys.deviceSerial) }
    }

    var deviceMAC: String? {
        get { defaults.string(forKey: Keys.deviceMAC) }
        set { defaults.set(newValue, forKey: Keys.deviceMAC) }
    }

    var clientId: Int {
        get { defaults.integer(forKey: Keys.clientId) }
        set { defaults.set(newValue, forKey: Keys.clientId) }
    }

    var clientName: String {
        get { defaults.string(forKey: Keys.clientName) ?? "" }
        set { defaults.set(newValue, forKey: Keys.clientName) }
    }

    var authToken: String {
        get { defaults.string(forKey: Keys.authToken) ?? "" }
        set { defaults.set(newValue, forKey: Keys.authToken) }
    }

    var alertCount: Int {
        get { defaults.integer(forKey: Keys.alertCount) }
        set { defaults.set(newValue, forKey: Keys.alertCount) }
    }

    var lastAlertTime: Date? {
        get { defaults.object(forKey: Keys.lastAlertTime) as? Date }
        set { defaults.set(newValue, forKey: Keys.lastAlertTime) }
    }

    struct Contact: Codable {
        let id: Int
        let order: Int
        let name: String
        let relationshipLabel: String
        let phone: String

        enum CodingKeys: String, CodingKey {
            case id, order, name, phone
            case relationshipLabel = "relationship_label"
        }
    }

    var contacts: [Contact] {
        get {
            guard let data = defaults.data(forKey: Keys.contacts) else { return [] }
            return (try? JSONDecoder().decode([Contact].self, from: data)) ?? []
        }
        set {
            let data = try? JSONEncoder().encode(newValue)
            defaults.set(data, forKey: Keys.contacts)
        }
    }
}
