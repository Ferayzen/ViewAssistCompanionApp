# Home Assistant Video Call Triggering

This document explains how to trigger video calls from Home Assistant without any in-app button, using server-side events. The VACA app listens to Home Assistant events in the background and reacts for both the caller and callee devices.

## Overview

- The VACA app runs a background websocket client while the foreground service is active.
- Home Assistant sends a `vaca_start_call` event with caller and target device IDs.
- The caller device opens the Video Call screen and starts the call.
- The callee device receives the offer event and shows an incoming-call notification with Accept/Decline actions.

## Required Device IDs

Each device uses its UUID as the call identity.

- Open the app on each device and find the "UUID" value on the Connect screen.
- Use these UUIDs as `caller_uuid` and `target_device` in Home Assistant.

## Home Assistant Setup

### 1) Event fire service (recommended)

Create a Home Assistant script or automation that fires the event. Example:

```yaml
alias: Start VACA Video Call
sequence:
  - service: event.fire
    data:
      event_type: vaca_start_call
      event_data:
        caller_uuid: "CALLER_DEVICE_UUID"
        target_device: "TARGET_DEVICE_UUID"
mode: single
```

### 2) Use the script from a dashboard button

```yaml
type: button
name: Call Living Room
icon: mdi:video
show_state: false
tap_action:
  action: call-service
  service: script.start_vaca_video_call
```

Replace `script.start_vaca_video_call` with the script entity ID created in step 1.

## Call Flow

1) Home Assistant fires `vaca_start_call` with `caller_uuid` and `target_device`.
2) The caller device opens the Video Call screen and starts the call.
3) The caller posts `vaca_webrtc_offer` to HA.
4) The callee receives the offer and shows an incoming call notification.
5) Accept launches the Video Call screen and answers the call.

## Testing Guidelines

1) Confirm both devices are paired and connected to Home Assistant.
2) Ensure the foreground service is running on both devices (normal app start shows a persistent notification indicating the service is active).
3) Verify the signaling client is connected by checking the Connect screen: the `Signaling` field should show `Connected` if the websocket is authenticated.
4) From Developer Tools > Events in Home Assistant, paste the exact event payload:

```yaml
# Example exact payload to paste into Developer Tools → Events
event_type: vaca_start_call
event_data:
  caller_uuid: "11111111-2222-3333-4444-555555555555"
  target_device: "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
```

Replace the UUIDs using the values from each device's Connect screen.

5) After firing the event: the caller device should open the call screen and the callee should receive an incoming call notification.
6) Accept on the callee device and confirm audio/video connects.

If nothing happens:
- Check the Connect screen on both devices and ensure `Signaling` reads `Connected`.
- If `Signaling` is `Disconnected`, confirm the app has a valid Home Assistant access token (Settings) and that HA is reachable from the device.
- Inspect app logs (adb logcat) for WebSocket/auth errors.

## Troubleshooting

- No reaction on caller: check that the app is running and has a valid Home Assistant access token.
- No incoming notification: confirm the callee device UUID is correct and the device is online.
- No media: verify camera/microphone permissions and that both devices are on the same network or can reach each other.

## Notes

- This flow is server-triggered and does not require any in-app button.
- The app listens for Home Assistant events in the background service; the service must be running for triggers to be received.
