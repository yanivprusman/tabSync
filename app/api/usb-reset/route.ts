import { readFile, writeFile } from "node:fs/promises";
import { setTimeout as sleep } from "node:timers/promises";
import { authorize } from "@/lib/auth";

// Brings back the desktop's keyboard and mouse when the ASM1074 hub they hang behind has hung.
// The kernel gives up on the port ("usb1-port10: unable to enumerate USB device") and replugging
// the hub downstream cannot revive it, so the only fix short of a reboot is power-cycling that
// port. This has to work from the phone: the keyboard and mouse are exactly what is missing.
//
// The port is named outright because a hung hub is absent from the USB tree and can't be looked
// up by vendor id. This server runs on the desktop as root, which is what the write needs.
const PORT_DISABLE = "/sys/bus/usb/devices/usb1/1-0:1.0/usb1-port10/disable";
const POWER_OFF_MS = 2000;
// Measured 2026-11-06: keyboard after about 2 s, receiver after about 8 s. Off time plus this wait
// stays under the phone's 20 s read timeout.
const WAIT_FOR_DEVICES_MS = 15000;

async function devicesPresent() {
  const devices = await readFile("/proc/bus/input/devices", "utf8");
  return {
    keyboard: devices.includes('Name="Corsair CORSAIR K100'),
    mouse: devices.includes('Name="Logitech USB Receiver Mouse"'),
  };
}

// POST /api/usb-reset answers with what came back. A device still missing is not a server error:
// the hub may simply be unplugged, and the phone says so.
export async function POST(request: Request) {
  const denied = authorize(request);
  if (denied) return Response.json({ error: denied }, { status: 401 });
  try {
    await writeFile(PORT_DISABLE, "1");
    try {
      await sleep(POWER_OFF_MS);
    } finally {
      await writeFile(PORT_DISABLE, "0");
    }
    const deadline = Date.now() + WAIT_FOR_DEVICES_MS;
    let present = await devicesPresent();
    while (!(present.keyboard && present.mouse) && Date.now() < deadline) {
      await sleep(500);
      present = await devicesPresent();
    }
    return Response.json(present);
  } catch (e) {
    return Response.json({ error: (e as Error).message }, { status: 502 });
  }
}
