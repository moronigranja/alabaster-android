
import ctypes, os, struct, sys, time

UINPUT_MAX_NAME_SIZE = 80
EV_SYN, EV_KEY, EV_ABS = 0x00, 0x01, 0x03
SYN_REPORT = 0
class input_event(ctypes.Structure):
    _fields_ = [("time", ctypes.c_long*2), ("type", ctypes.c_ushort), ("code", ctypes.c_ushort), ("value", ctypes.c_int)]
SIZE = ctypes.sizeof(input_event)

UI_SET_EVBIT = 0x40045564
UI_SET_KEYBIT = 0x40045565
UI_SET_ABSBIT = 0x40045567
UI_DEV_CREATE = 0x5501
UI_DEV_DESTROY = 0x5502
UI_DEV_SETUP = 0x405c5503
UI_ABS_SETUP = 0x401c5504

class uinput_setup(ctypes.Structure):
    _fields_ = [("id", ctypes.c_uint16*4), ("name", ctypes.c_char*UINPUT_MAX_NAME_SIZE), ("ff_effects_max", ctypes.c_uint32)]
class uinput_abs_setup(ctypes.Structure):
    _fields_ = [("code", ctypes.c_uint16), ("pad", ctypes.c_uint16), ("absinfo", ctypes.c_int*6)]

BTN = {'south':0x130,'east':0x131,'north':0x133,'west':0x134,'tl':0x136,'tr':0x137,
       'select':0x13a,'start':0x13b,'mode':0x13c,'thumbl':0x13d,'thumbr':0x13e,
       'tl2':0x138,'tr2':0x139,'dpad_up':0x220,'dpad_down':0x221,'dpad_left':0x222,'dpad_right':0x223}
ABS = {'x':0,'y':1,'z':2,'rx':3,'ry':4,'rz':5,'hat0x':16,'hat0y':17,'gas':9,'brake':10}

def ioc(fd, req, arg):
    import fcntl
    fcntl.ioctl(fd, req, struct.pack('I', arg) if isinstance(arg,int) else arg)

def create(name, vendor, product, mode, extra_btns=()):
    fd = os.open('/dev/uinput', os.O_WRONLY | os.O_NONBLOCK)
    import fcntl
    for ev in (EV_KEY, EV_ABS, EV_SYN):
        fcntl.ioctl(fd, UI_SET_EVBIT, ev)
    for b in list(BTN.values()) + list(extra_btns):
        fcntl.ioctl(fd, UI_SET_KEYBIT, b)
    axes = ['x','y','z','rx','ry','rz','hat0x','hat0y']
    for a in axes:
        fcntl.ioctl(fd, UI_SET_ABSBIT, ABS[a])
    for a in axes:
        if a.startswith('hat'):
            ai = (0, -1, 1, 0, 0, 0)
        else:
            ai = (0, -32768, 32767, 16, 128, 0)
        s = uinput_abs_setup()
        s.code = ABS[a]
        for i,v in enumerate(ai): s.absinfo[i] = v
        fcntl.ioctl(fd, UI_ABS_SETUP, bytes(s))
    st = uinput_setup()
    st.id[0] = 0x03  # BUS_USB
    st.id[1] = vendor
    st.id[2] = product
    st.id[3] = 0x0110
    st.name = name.encode()
    fcntl.ioctl(fd, UI_DEV_SETUP, bytes(st))
    fcntl.ioctl(fd, UI_DEV_CREATE, 0)
    return fd, name

def emit(fd):
    ev = input_event()
    ev.type = EV_SYN; ev.code = SYN_REPORT; ev.value = 0
    os.write(fd, bytes(ev))

which = sys.argv[1] if len(sys.argv) > 1 else 'xbox'
if which == 'xbox':
    fd,_ = create("Microsoft X-Box 360 pad", 0x045e, 0x028e, 'xinput')
else:
    fd,_ = create("8BitDo Ultimate Wireless / Pro 2 Wired Controller", 0x2dc8, 0x3106, 'dinput')
print("created", which, flush=True)
try:
    while True:
        time.sleep(3600)
except KeyboardInterrupt:
    pass
