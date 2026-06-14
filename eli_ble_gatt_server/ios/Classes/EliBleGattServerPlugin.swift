import Flutter
import UIKit

public class EliBleGattServerPlugin: NSObject, FlutterPlugin, FlutterStreamHandler {

    private let manager = BleGattServerManager()
    private var eventSink: FlutterEventSink?

    public static func register(with registrar: FlutterPluginRegistrar) {
        let instance = EliBleGattServerPlugin()

        let methodChannel = FlutterMethodChannel(
            name: "eli_ble_gatt_server",
            binaryMessenger: registrar.messenger()
        )
        registrar.addMethodCallDelegate(instance, channel: methodChannel)

        let eventChannel = FlutterEventChannel(
            name: "eli_ble_gatt_server/events",
            binaryMessenger: registrar.messenger()
        )
        eventChannel.setStreamHandler(instance)

        // El manager emite eventos hacia el sink activo (ya en main thread).
        instance.manager.onEvent = { [weak instance] event in
            instance?.eventSink?(event)
        }
    }

    // MARK: - FlutterPlugin

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "getPlatformVersion":
            result("iOS " + UIDevice.current.systemVersion)

        case "configureServer":
            guard let args = call.arguments as? [String: Any],
                  let serviceUuid = args["serviceUuid"] as? String,
                  let characteristicUuid = args["characteristicUuid"] as? String else {
                result(FlutterError(code: "BAD_ARGS",
                                    message: "serviceUuid and characteristicUuid are required",
                                    details: nil))
                return
            }
            let deviceName = args["deviceName"] as? String
            let payload = args["payload"] as? [String: Any]
            manager.configure(serviceUuid: serviceUuid,
                              characteristicUuid: characteristicUuid,
                              deviceName: deviceName,
                              payload: payload)
            result(true)

        case "startServer":
            manager.start()
            result(true)

        case "stopServer":
            manager.stop()
            result(true)

        case "getServerStatus":
            result(manager.statusMap())

        case "sendMessage":
            guard manager.isActive else {
                result(FlutterError(code: "SERVICE_NOT_RUNNING",
                                    message: "BLE server is not running",
                                    details: nil))
                return
            }
            let args = call.arguments as? [String: Any]
            let message = args?["message"] as? String ?? ""
            manager.sendMessage(message)
            result(true)

        default:
            result(FlutterMethodNotImplemented)
        }
    }

    // MARK: - FlutterStreamHandler

    public func onListen(withArguments arguments: Any?,
                         eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        self.eventSink = events
        return nil
    }

    public func onCancel(withArguments arguments: Any?) -> FlutterError? {
        self.eventSink = nil
        return nil
    }
}
