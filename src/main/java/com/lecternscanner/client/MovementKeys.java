package com.lecternscanner.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * Baritone-style movement: keep a KeyboardInput, force W/jump/sprint/etc.
 * Forced state is applied inside {@link BotKeyboardInput#tick()} so it wins
 * regardless of ClientTick Pre/Post ordering vs KeyMapping.setAll().
 */
public final class MovementKeys {
    private static boolean forward;
    private static boolean back;
    private static boolean left;
    private static boolean right;
    private static boolean jump;
    private static boolean sneak;
    private static boolean sprint;
    private static boolean attack;
    private static boolean use;
    private static boolean active;

    private MovementKeys() {
    }

    public static void clear() {
        forward = back = left = right = jump = sneak = sprint = attack = use = false;
        active = false;
        applyToOptions();
    }

    public static void setMove(boolean fwd, boolean jmp, boolean snk, boolean spr) {
        active = true;
        forward = fwd;
        back = false;
        left = false;
        right = false;
        jump = jmp;
        sneak = snk;
        sprint = spr && fwd;
        applyToOptions();
    }

    public static void setAttack(boolean atk) {
        attack = atk;
        if (atk) {
            active = true;
        }
        Minecraft.getInstance().options.keyAttack.setDown(atk);
    }

    public static void setUse(boolean u) {
        use = u;
        if (u) {
            active = true;
        }
        Minecraft.getInstance().options.keyUse.setDown(u);
    }

    public static boolean isActive() {
        return active;
    }

    /** Call every Pre tick while bot runs — keeps KeyMapping state warm. */
    public static void tickApply() {
        if (!active) {
            return;
        }
        applyToOptions();
        ensureKeyboardInput();
    }

    private static void applyToOptions() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.keyUp.setDown(forward);
        mc.options.keyDown.setDown(back);
        mc.options.keyLeft.setDown(left);
        mc.options.keyRight.setDown(right);
        mc.options.keyJump.setDown(jump);
        mc.options.keyShift.setDown(sneak);
        mc.options.keySprint.setDown(sprint);
        mc.options.keyAttack.setDown(attack);
        mc.options.keyUse.setDown(use);
    }

    public static void ensureKeyboardInput() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        if (!(player.input instanceof BotKeyboardInput)) {
            player.input = new BotKeyboardInput(Minecraft.getInstance().options);
        }
    }

    /**
     * Forces bot keys immediately before reading Options into moveVector —
     * same moment vanilla movement consumes input.
     */
    public static final class BotKeyboardInput extends KeyboardInput {
        public BotKeyboardInput(Options options) {
            super(options);
        }

        @Override
        public void tick() {
            if (active) {
                applyToOptions();
            }
            super.tick();
            if (active) {
                // Belt-and-suspenders: write presses/vector directly in case
                // KeyMapping.isDown() is false due to conflict context.
                this.keyPresses = new Input(forward, back, left, right, jump, sneak, sprint);
                float f = impulse(forward, back);
                float f1 = impulse(left, right);
                this.moveVector = new Vec2(f1, f);
                if (f != 0.0F || f1 != 0.0F) {
                    this.moveVector = this.moveVector.normalized();
                }
            }
        }

        private static float impulse(boolean a, boolean b) {
            if (a == b) {
                return 0.0F;
            }
            return a ? 1.0F : -1.0F;
        }
    }
}
