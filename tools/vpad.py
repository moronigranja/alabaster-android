import ctypes, os, struct, sys, time, json, fcntl

UINPUT_MAX_NAME_SIZE = 80
EV_SYN, EV_KEY, EV_ABS = 0x00, 0x01, 0x03
SYN_REPORT = 0

class input_event(ctypes.Structure):
    _fields_ = [("time", ctypes.c_long * 2), ("type", ctypes.c_ushort), ("code", ctypes.c_ushort), ("value", ctypes.c_int)]

UI_SET_EVBIT = 0x40045564
UI_SET_KEYBIT = 0x40045565
UI_SET_ABSBIT = 0x40045567
UI_DEV_CREATE = 0x5501
UI_DEV_SETUP = 0x405c5503
UI_ABS_SETUP = 0x401c5504

class uinput_setup(ctypes.Structure):
    _fields_ = [("id", ctypes.c_uint16 * 4), ("name", ctypes.c_char * UINPUT_MAX_NAME_SIZE), ("ff_effects_max", ctypes.c_uint32)]

class uinput_abs_setup(ctypes.Structure):
    _fields_ = [("code", ctypes.c_uint16), ("pad", ctypes.c_uint16), ("absinfo", ctypes.c_int * 6)]

# button order = joydev button index order
BTNS = [0x130, 0x131, 0x133, 0x134, 0x136, 0x137, 0x13a, 0x13b, 0x13c, 0x13d, 0x13e]
AXES = {'X': 0, 'Y': 1, 'Z': 2, 'RX': 3, 'RY': 4, 'RZ': 5, 'HAT0X': 16, 'HAT0Y': 17}
AXORDER = ['X', 'Y', 'Z', 'RX', 'RY', 'RZ', 'HAT0X', 'HAT0Y']


def create(name, vendor, product):
    fd = os.open('/dev/uinput', os.O_WRONLY | os.O_NONBLOCK)
    for ev in (EV_KEY, EV_ABS, EV_SYN):
        fcntl.ioctl(fd, UI_SET_EVBIT, ev)
    for b in BTNS:
        fcntl.ioctl(fd, UI_SET_KEYBIT, b)
    for a in AXORDER:
        fcntl.ioctl(fd, UI_SET_ABSBIT, AXES[a])
    for a in AXORDER:
        ai = (0, -1, 1, 0, 0, 0) if a.startswith('HAT') else (0, -32768, 32767, 16, 128, 0)
        s = uinput_abs_setup()
        s.code = AXES[a]
        for i, v in enumerate(ai):
            s.absinfo[i] = v
        fcntl.ioctl(fd, UI_ABS_SETUP, bytes(s))
    st = uinput_setup()
    st.id[0] = 0x03
    st.id[1] = vendor
    st.id[2] = product
    st.id[3] = 0x0114
    st.name = name.encode()
    fcntl.ioctl(fd, UI_DEV_SETUP, bytes(st))
    fcntl.ioctl(fd, UI_DEV_CREATE, 0)
    return fd


def send(fd, etype, code, value):
    ev = input_event()
    ev.type = etype
    ev.code = code
    ev.value = value
    os.write(fd, bytes(ev))
    ev.type = EV_SYN
    ev.code = SYN_REPORT
    ev.value = 0
    os.write(fd, bytes(ev))


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else 'dinput'
    if mode == 'xbox':
        fd = create("Microsoft X-Box 360 pad", 0x045e, 0x028e)
    elif mode == 'unknown':
        fd = create("Generic USB Joystick", 0x1234, 0x5678)
    else:
        nm = sys.argv[2] if len(sys.argv) > 2 else "8BitDo Ultimate Wireless / Pro 2 Wired Controller"
        fd = create(nm, 0x2dc8, 0x3106)
    cmdfile = '/tmp/gp/cmd_%s.json' % mode
    print('created %s (%s)' % (mode, cmdfile), flush=True)
    state = {'buttons': {}, 'axes': {}}
    while True:
        try:
            if os.path.exists(cmdfile):
                with open(cmdfile) as f:
                    cmd = json.load(f)
                for k, v in (cmd.get('buttons') or {}).items():
                    k = int(k)
                    if state['buttons'].get(k, 0) != v:
                        state['buttons'][k] = v
                        send(fd, EV_KEY, BTNS[k], int(v))
                for k, v in (cmd.get('axes') or {}).items():
                    k = int(k)
                    if state['axes'].get(k, 0) != v:
                        state['axes'][k] = v
                        send(fd, EV_ABS, AXES[AXORDER[k]], int(v))
        except Exception as e:
            print('cmd error', e, flush=True)
        time.sleep(0.15)


main()
