import Foundation

/// Cliente HTTP para comunicarse con el servidor SiempreCerca.
/// Maneja login automatico, envio de alertas y sincronizacion de contactos.
class APIClient {

    static let shared = APIClient()
    private let session = URLSession.shared
    private let prefs = AppPreferences.shared

    // MARK: - Login

    /// Hace login automatico con las credenciales precargadas.
    /// Guarda el JWT para requests posteriores.
    func login(completion: ((Bool) -> Void)? = nil) {
        let url = URL(string: "\(Config.baseURL)/api/auth/login")!
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        let body: [String: String] = [
            "email": Config.loginEmail,
            "password": Config.loginPassword
        ]
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)

        session.dataTask(with: request) { [weak self] data, response, error in
            guard let data = data,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let token = json["access_token"] as? String else {
                print("[APIClient] Login fallido: \(error?.localizedDescription ?? "sin datos")")
                completion?(false)
                return
            }

            self?.prefs.authToken = token
            print("[APIClient] Login exitoso, JWT renovado")
            completion?(true)
        }.resume()
    }

    // MARK: - Fetch Client

    /// Obtiene los datos de un paciente por ID.
    func fetchClient(id: Int, completion: @escaping (String?) -> Void) {
        ensureToken {
            let url = URL(string: "\(Config.baseURL)/api/clients/\(id)")!
            var request = URLRequest(url: url)
            request.setValue("Bearer \(self.prefs.authToken)", forHTTPHeaderField: "Authorization")

            self.session.dataTask(with: request) { data, response, error in
                guard let data = data,
                      let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                    completion(nil)
                    return
                }

                let name = json["full_name"] as? String
                    ?? json["name"] as? String
                    ?? "\(json["first_name"] as? String ?? "") \(json["last_name"] as? String ?? "")"
                completion(name.trimmingCharacters(in: .whitespaces).isEmpty ? nil : name)
            }.resume()
        }
    }

    // MARK: - Send Alert

    /// Envia alerta SOS al servidor via webhook (mismo formato que la app FLIC).
    func sendAlert(deviceSerial: String, bleData: Data, completion: ((Bool) -> Void)? = nil) {
        ensureToken {
            let url = URL(string: "\(Config.baseURL)/api/webhooks/flic/alert")!
            var request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.setValue(Config.webhookSecret, forHTTPHeaderField: "X-Webhook-Secret")
            request.setValue(deviceSerial, forHTTPHeaderField: "button-serial-number")
            request.setValue("Flic \(deviceSerial)", forHTTPHeaderField: "button-name")
            request.setValue(bleData.map { String(format: "%02X", $0) }.joined(separator: " "),
                           forHTTPHeaderField: "X-BLE-Raw")

            let body: [String: String] = [
                "event": "sos",
                "source": "siemprecerca_monitor_ios",
                "appVersion": "1.0.0"
            ]
            request.httpBody = try? JSONSerialization.data(withJSONObject: body)

            self.session.dataTask(with: request) { data, response, error in
                let httpResponse = response as? HTTPURLResponse
                if httpResponse?.statusCode == 401 {
                    // JWT expirado, renovar y reintentar
                    self.login { success in
                        if success {
                            self.sendAlert(deviceSerial: deviceSerial, bleData: bleData, completion: completion)
                        }
                    }
                    return
                }

                let success = httpResponse?.statusCode == 200
                print("[APIClient] Alerta enviada: \(success ? "OK" : "ERROR \(httpResponse?.statusCode ?? 0)")")
                completion?(success)
            }.resume()
        }
    }

    // MARK: - Sync Contacts

    /// Sincroniza los contactos de emergencia del paciente desde el servidor.
    func syncContacts(completion: ((Bool) -> Void)? = nil) {
        let clientId = prefs.clientId
        guard clientId > 0 else {
            completion?(false)
            return
        }

        ensureToken {
            let url = URL(string: "\(Config.baseURL)/api/clients/\(clientId)/contacts")!
            var request = URLRequest(url: url)
            request.setValue("Bearer \(self.prefs.authToken)", forHTTPHeaderField: "Authorization")

            self.session.dataTask(with: request) { data, response, error in
                guard let data = data else {
                    completion?(false)
                    return
                }

                if let contacts = try? JSONDecoder().decode([AppPreferences.Contact].self, from: data) {
                    self.prefs.contacts = contacts
                    print("[APIClient] Contactos sincronizados: \(contacts.count)")
                    completion?(true)
                } else {
                    completion?(false)
                }
            }.resume()
        }
    }

    // MARK: - Private

    private func ensureToken(_ then: @escaping () -> Void) {
        if prefs.authToken.isEmpty {
            login { _ in then() }
        } else {
            then()
        }
    }
}
