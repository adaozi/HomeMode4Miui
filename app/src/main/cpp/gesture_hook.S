// ABI-transparent AArch64 trampoline for on_swipe_process.
// Structure adapted from HyperOS4SwipeGate swipe_hook_entry.S (MIT,
// github.com/PzHown/HyperOS4SwipeGate), GPL-3.0 project used with attribution.
//
// The only intentionally observed registers are w1 (readyFinish), w2 (side)
// and s0 (horizontal distance); x0..x8, q0..q7 and x30 are saved/restored so
// point-representation changes never require a new C++ prototype. x9..x18 are
// caller-saved by AAPCS64, so the C helper may clobber them.

.text
.align 2

.hidden gesture_obs_enter
.hidden g_gesture_original
.global gesture_on_swipe_hook
.type gesture_on_swipe_hook, %function

gesture_on_swipe_hook:
    sub sp, sp, #0x110

    stp x0, x1, [sp, #0x00]
    stp x2, x3, [sp, #0x10]
    stp x4, x5, [sp, #0x20]
    stp x6, x7, [sp, #0x30]
    str x8, [sp, #0x40]
    str x30, [sp, #0x48]

    stp q0, q1, [sp, #0x50]
    stp q2, q3, [sp, #0x70]
    stp q4, q5, [sp, #0x90]
    stp q6, q7, [sp, #0xb0]

    // void gesture_obs_enter(uint32_t readyFinish, uint32_t side, float dist)
    ldr w0, [sp, #0x08]
    ldr w1, [sp, #0x10]
    ldr s0, [sp, #0x50]
    bl gesture_obs_enter

    ldp q0, q1, [sp, #0x50]
    ldp q2, q3, [sp, #0x70]
    ldp q4, q5, [sp, #0x90]
    ldp q6, q7, [sp, #0xb0]

    ldp x0, x1, [sp, #0x00]
    ldp x2, x3, [sp, #0x10]
    ldp x4, x5, [sp, #0x20]
    ldp x6, x7, [sp, #0x30]
    ldr x8, [sp, #0x40]
    ldr x30, [sp, #0x48]

    add sp, sp, #0x110

    adrp x17, g_gesture_original
    ldr x17, [x17, :lo12:g_gesture_original]
    br x17
