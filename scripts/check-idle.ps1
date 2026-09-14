$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runner = 'com.awxds.countdowntimer.test/androidx.test.runner.AndroidJUnitRunner'
$testClass = 'com.awxds.countdowntimer.TimerScenarioSetup'
$results = [System.Collections.Generic.List[string]]::new()
try {
    & adb shell am instrument -w -e class $testClass -e duration 7200000 -e mode VISUAL $runner | Out-Null
    & adb shell am start -W -n com.awxds.countdowntimer/.MainActivity | Out-Null
    & adb shell input keyevent KEYCODE_HOME
    & adb shell input keyevent KEYCODE_SLEEP
    & adb shell dumpsys battery unplug
    $idle = & adb shell dumpsys deviceidle force-idle
    if ($idle -notmatch 'Now forced') { throw "Could not enter deep idle: $idle" }
    $results.Add('Entered deep IDLE with screen off and a two-hour timer.')
    & adb shell am instrument -w -e class $testClass -e duration 10000 -e mode SOUND $runner | Out-Null
    [xml]$initial = (& adb shell run-as com.awxds.countdowntimer cat shared_prefs/timer.xml) -join "`n"
    $deadline = [long]$initial.SelectSingleNode('/map/long[@name="deadline"]').value
    $until = [DateTime]::UtcNow.AddSeconds(35)
    do {
        Start-Sleep -Seconds 1
        [xml]$state = (& adb shell run-as com.awxds.countdowntimer cat shared_prefs/timer.xml) -join "`n"
        $phase = $state.SelectSingleNode('/map/string[@name="phase"]').InnerText
    } while ($phase -ne 'FINISHED' -and [DateTime]::UtcNow -lt $until)
    if ($phase -ne 'FINISHED') { throw 'Timer did not finish from idle.' }
    $ringing = $state.SelectSingleNode('/map/boolean[@name="ringing"]').value
    if ($ringing -ne 'true') { throw 'Timer finished but ringing was not active.' }
    $finish = [long]$state.SelectSingleNode('/map/long[@name="ringDeadline"]').value - 60000
    $results.Add("Short alarm scheduled from idle finished; lateness=$($finish - $deadline) ms; ringing=true.")
    $service = (& adb shell dumpsys activity services com.awxds.countdowntimer) -join "`n"
    if ($service -notmatch 'isForeground=true') { throw 'Ringing foreground service missing.' }
    $results.Add('Ringing foreground service active while screen remained off.')
    $results | Set-Content -Encoding utf8 (Join-Path $projectRoot 'verification\deep-idle.txt')
    $results
} finally {
    & adb shell dumpsys deviceidle unforce | Out-Null
    & adb shell dumpsys battery reset
    & adb shell input keyevent KEYCODE_WAKEUP
    & adb shell wm dismiss-keyguard
}
