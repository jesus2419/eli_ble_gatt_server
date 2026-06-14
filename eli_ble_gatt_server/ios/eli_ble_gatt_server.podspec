#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
# Run `pod lib lint eli_ble_gatt_server.podspec` to validate before publishing.
#
Pod::Spec.new do |s|
  s.name             = 'eli_ble_gatt_server'
  s.version          = '0.1.0'
  s.summary          = 'Turn the device into a BLE GATT server (peripheral).'
  s.description      = <<-DESC
A Flutter plugin that turns the device into a BLE GATT server (peripheral):
advertises a configurable service/characteristic and supports READ/WRITE/NOTIFY.
                       DESC
  s.homepage         = 'https://github.com/jesus2419/eli_ble_gatt_server'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'jesus2419' => 'jesus.osorio@urbani.com.mx' }
  s.source           = { :path => '.' }
  s.source_files = 'Classes/**/*'
  s.dependency 'Flutter'
  s.frameworks = 'CoreBluetooth'
  s.platform = :ios, '13.0'

  # Flutter.framework does not contain a i386 slice.
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', 'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386' }
  s.swift_version = '5.0'

  # If your plugin requires a privacy manifest, for example if it uses any
  # required reason APIs, update the PrivacyInfo.xcprivacy file to describe your
  # plugin's privacy impact, and then uncomment this line. For more information,
  # see https://developer.apple.com/documentation/bundleresources/privacy_manifest_files
  # s.resource_bundles = {'eli_ble_gatt_server_privacy' => ['Resources/PrivacyInfo.xcprivacy']}
end
