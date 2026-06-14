import Foundation
import CoreBluetooth

/// Implementa el rol periférico (servidor GATT) en iOS con CoreBluetooth,
/// replicando el contrato del lado Android (`EliBleGattServerService.kt`).
///
/// Limitaciones de iOS asumidas:
/// - No se puede cambiar el nombre Bluetooth real del dispositivo; `deviceName`
///   se usa solo como local name anunciado (`CBAdvertisementDataLocalNameKey`).
/// - No hay MAC ni nombre del central conectado; se expone `central.identifier`
///   (UUID) como `address` y `"unknown"` como `name`.
/// - No hay manufacturer data en el advertising del periférico.
/// - "Conexión" se detecta vía suscripción a notificaciones (`didSubscribeTo`).
class BleGattServerManager: NSObject, CBPeripheralManagerDelegate {

    // MARK: - Emisión de eventos hacia Flutter

    /// Closure invocado para emitir un evento al EventChannel. Siempre en main thread.
    var onEvent: (([String: Any]) -> Void)?

    private func emit(_ event: [String: Any]) {
        let cb = onEvent
        DispatchQueue.main.async {
            cb?(event)
        }
    }

    // MARK: - Configuración

    private var serviceUUID: CBUUID?
    private var characteristicUUID: CBUUID?
    private var deviceName: String = "BLE-Server-Flutter"
    private var payload: [String: Any]?

    // MARK: - Estado CoreBluetooth

    private var peripheralManager: CBPeripheralManager?
    private var characteristic: CBMutableCharacteristic?
    private var subscribedCentrals: [CBCentral] = []

    private var serverActive = false
    private var advertisingActive = false
    private var wantsToStart = false

    private let chunkSize = 20

    // Cola de notificaciones pendientes (backpressure de updateValue).
    // Cada entrada: trozo de datos a enviar a los suscriptores y metadatos del evento ble_tx.
    private struct PendingChunk {
        let data: Data
        let offset: Int
        let total: Int
        let central: CBCentral? // nil = todos los suscriptores
    }
    private var pendingChunks: [PendingChunk] = []

    // MARK: - API pública (invocada por el plugin)

    func configure(serviceUuid: String,
                   characteristicUuid: String,
                   deviceName: String?,
                   payload: [String: Any]?) {
        self.serviceUUID = CBUUID(string: serviceUuid)
        self.characteristicUUID = CBUUID(string: characteristicUuid)
        self.deviceName = deviceName ?? "BLE-Server-Flutter"
        self.payload = payload
    }

    func start() {
        wantsToStart = true
        if peripheralManager == nil {
            // Su estado llega de forma asíncrona en peripheralManagerDidUpdateState.
            peripheralManager = CBPeripheralManager(delegate: self, queue: nil)
        } else if peripheralManager?.state == .poweredOn {
            beginServing()
        }
    }

    func stop() {
        wantsToStart = false
        pendingChunks.removeAll()

        if let pm = peripheralManager {
            if pm.isAdvertising {
                pm.stopAdvertising()
            }
            pm.removeAllServices()
        }

        subscribedCentrals.removeAll()
        characteristic = nil
        serverActive = false
        advertisingActive = false
        peripheralManager = nil

        emit(["type": "service_destroyed"])
    }

    func sendMessage(_ message: String) {
        guard !subscribedCentrals.isEmpty else {
            NSLog("[EliBleGattServer] No connected devices")
            return
        }
        let data = Data(message.utf8)
        enqueueChunks(of: data, to: nil)
    }

    var isActive: Bool { serverActive }

    func statusMap() -> [String: Any] {
        return [
            "isActive": serverActive,
            "advertising": advertisingActive,
            "connectedDevices": subscribedCentrals.count,
            "deviceName": deviceName,
            "serviceUuid": serviceUUID?.uuidString as Any,
            "characteristicUuid": characteristicUUID?.uuidString as Any
        ]
    }

    // MARK: - Setup interno

    private func beginServing() {
        setupService()
        startAdvertising()
    }

    private func setupService() {
        guard let serviceUUID = serviceUUID,
              let characteristicUUID = characteristicUUID,
              let pm = peripheralManager else { return }

        // En iOS NO se crea manualmente el descriptor CCCD 2902: el sistema
        // gestiona las suscripciones (didSubscribeTo / didUnsubscribeFrom).
        let char = CBMutableCharacteristic(
            type: characteristicUUID,
            properties: [.read, .write, .notify],
            value: nil,
            permissions: [.readable, .writeable]
        )
        self.characteristic = char

        let service = CBMutableService(type: serviceUUID, primary: true)
        service.characteristics = [char]

        pm.removeAllServices()
        pm.add(service)
    }

    private func startAdvertising() {
        guard let serviceUUID = serviceUUID, let pm = peripheralManager else { return }

        let advertisement: [String: Any] = [
            CBAdvertisementDataServiceUUIDsKey: [serviceUUID],
            CBAdvertisementDataLocalNameKey: deviceName
            // iOS no admite manufacturer data en el advertising del periférico.
        ]
        pm.startAdvertising(advertisement)
    }

    private func emitServerInfo() {
        emit([
            "type": "server_info",
            "device_name": deviceName,
            "service_uuid": serviceUUID?.uuidString as Any,
            "characteristic_uuid": characteristicUUID?.uuidString as Any,
            "advertising": advertisingActive,
            "server_active": serverActive,
            "connected_devices": subscribedCentrals.count
        ])
    }

    // MARK: - Envío de notificaciones (chunking + backpressure)

    /// Trocea `data` en bloques de 20 bytes y los encola para envío por NOTIFY.
    /// `central == nil` notifica a todos los suscriptores; en otro caso solo a `central`.
    private func enqueueChunks(of data: Data, to central: CBCentral?) {
        let total = data.count
        var offset = 0
        while offset < total {
            let size = min(chunkSize, total - offset)
            let chunk = data.subdata(in: offset..<(offset + size))
            offset += size
            pendingChunks.append(PendingChunk(data: chunk, offset: offset, total: total, central: central))
        }
        flushPendingChunks()
    }

    /// Intenta vaciar la cola de trozos. Si `updateValue` devuelve `false`,
    /// detiene el envío y reanuda en `peripheralManagerIsReadyToUpdateSubscribers`.
    private func flushPendingChunks() {
        guard let pm = peripheralManager, let char = characteristic else { return }

        while let next = pendingChunks.first {
            let centrals: [CBCentral]? = next.central.map { [$0] }
            let sent = pm.updateValue(next.data, for: char, onSubscribedCentrals: centrals)
            if sent {
                pendingChunks.removeFirst()
                emit([
                    "type": "ble_tx",
                    "chunk_size": next.data.count,
                    "offset": next.offset,
                    "total": next.total
                ])
            } else {
                // Cola de transmisión llena: esperar a peripheralManagerIsReadyToUpdateSubscribers.
                break
            }
        }
    }

    // MARK: - CBPeripheralManagerDelegate

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        switch peripheral.state {
        case .poweredOn:
            if wantsToStart {
                beginServing()
            }
        default:
            serverActive = false
            advertisingActive = false
            emit(["type": "advertising", "status": "error"])
        }
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let error = error {
            serverActive = false
            advertisingActive = false
            NSLog("[EliBleGattServer] Advertising error: \(error.localizedDescription)")
            emit(["type": "advertising", "status": "error"])
        } else {
            serverActive = true
            advertisingActive = true
            emit(["type": "advertising", "status": "started"])
            emitServerInfo()
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        guard request.characteristic.uuid == characteristicUUID else {
            peripheral.respond(to: request, withResult: .attributeNotFound)
            return
        }

        let timestamp = Int(Date().timeIntervalSince1970 * 1000)
        let response = "Message from  - Time: \(timestamp)"
        let value = Data(response.utf8)

        guard request.offset <= value.count else {
            peripheral.respond(to: request, withResult: .invalidOffset)
            return
        }
        request.value = value.subdata(in: request.offset..<value.count)
        peripheral.respond(to: request, withResult: .success)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for request in requests where request.characteristic.uuid == characteristicUUID {
            let data = request.value ?? Data()
            let text = String(data: data, encoding: .utf8) ?? ""
            emit([
                "type": "ble_rx",
                "from": request.central.identifier.uuidString,
                "data": text
            ])
        }
        if let first = requests.first {
            peripheral.respond(to: first, withResult: .success)
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager,
                           central: CBCentral,
                           didSubscribeTo characteristic: CBCharacteristic) {
        if !subscribedCentrals.contains(where: { $0.identifier == central.identifier }) {
            subscribedCentrals.append(central)
        }
        emit([
            "type": "device_connected",
            "address": central.identifier.uuidString,
            "name": "unknown",
            "total": subscribedCentrals.count
        ])

        // Enviar payload inicial al recién suscrito (espeja onDescriptorWriteRequest).
        if let payload = payload,
           let json = try? JSONSerialization.data(withJSONObject: payload, options: []) {
            enqueueChunks(of: json, to: central)
        }

        emitServerInfo()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager,
                           central: CBCentral,
                           didUnsubscribeFrom characteristic: CBCharacteristic) {
        subscribedCentrals.removeAll { $0.identifier == central.identifier }
        emit([
            "type": "device_disconnected",
            "address": central.identifier.uuidString,
            "total": subscribedCentrals.count
        ])
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        flushPendingChunks()
    }
}
