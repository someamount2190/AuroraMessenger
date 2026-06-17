# emulator-p2p-test.ps1
#
# Makes two co-located Android emulators reachable to each other so they exchange
# messages/calls via the REAL production rendezvous (api.auroramessenger.com) for
# discovery + signalling, and REAL direct P2P for data -- no app shortcuts.
#
# Why this is needed: two emulators on one host can't reach each other's 10.0.2.x
# address. The intended architecture has each peer ADVERTISE a reachable address that
# the rendezvous hands out (real phones get this from NAT-PMP / port mapping). Here we
# provide the exact stand-in: forward each emulator's data-plane TCP port (8765) to a
# host port, and tell each emulator to advertise 10.0.2.2:<that host port> (the other
# emulator reaches it via its 10.0.2.2 gateway -> host -> adb forward -> the emulator).
#
# ---------------------------------------------------------------------------------
# ONE-TIME per emulator (manual, in the app -- persists in prefs, redo only after
# "Clear all data"):
#   Settings -> tap the version footer ("Aurora . x.y.z") 7x -> Developer options ->
#   "Advertised address (ip:port)" -> set the value below -> "Save advertised address"
#       emulator-5554 :  10.0.2.2:18554
#       emulator-5556 :  10.0.2.2:18556
#   Then restart the app (force-stop + relaunch) so it re-checks-in with that address.
# ---------------------------------------------------------------------------------
# EVERY TIME (adb forwards are lost when the adb server restarts / machine reboots):
#   run this script.
#
# After this, send a message between the paired emulators; it resolves the peer via
# the production /find and delivers over direct P2P through the host forward.

$ErrorActionPreference = "Stop"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }

$dataPort = 8765                       # TcpMessageServer.PORT (data-plane listener)
$map = [ordered]@{                     # emulator serial -> host port (== advertised port)
    "emulator-5554" = 18554
    "emulator-5556" = 18556
}

Write-Host "Setting up emulator P2P reachability (data port $dataPort)..." -ForegroundColor Cyan
foreach ($emu in $map.Keys) {
    $hostPort = $map[$emu]
    & $adb -s $emu forward "tcp:$hostPort" "tcp:$dataPort" | Out-Null
    Write-Host ("  {0}: host 127.0.0.1:{1} -> {0}:{2}   (advertise 10.0.2.2:{1})" -f $emu, $hostPort, $dataPort)
}

Write-Host ""
Write-Host "Active forwards:" -ForegroundColor Cyan
& $adb forward --list

Write-Host ""
Write-Host "Reminder: each emulator must have its Advertised address set to" -ForegroundColor Yellow
Write-Host "  10.0.2.2:<its host port>  (one-time dev-settings step in the app -- see header)." -ForegroundColor Yellow
Write-Host "Discovery still goes through the real server (api.auroramessenger.com)." -ForegroundColor Yellow
