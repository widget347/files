package wtf.bhopper.nonsense.module.impl.player;

import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import wtf.bhopper.nonsense.Nonsense;
import wtf.bhopper.nonsense.component.impl.player.RotationsComponent;
import wtf.bhopper.nonsense.event.EventLink;
import wtf.bhopper.nonsense.event.Listener;
import wtf.bhopper.nonsense.event.impl.client.EventTick;
import wtf.bhopper.nonsense.event.impl.packet.EventReceivePacket;
import wtf.bhopper.nonsense.event.impl.packet.EventSendPacket;
import wtf.bhopper.nonsense.event.impl.player.EventUpdate;
import wtf.bhopper.nonsense.event.impl.player.interact.EventClickAction;
import wtf.bhopper.nonsense.gui.hud.notification.Notification;
import wtf.bhopper.nonsense.gui.hud.notification.NotificationType;
import wtf.bhopper.nonsense.module.AbstractModule;
import wtf.bhopper.nonsense.module.ModuleCategory;
import wtf.bhopper.nonsense.module.ModuleInfo;
import wtf.bhopper.nonsense.module.impl.combat.KillAura;
import wtf.bhopper.nonsense.module.property.impl.BooleanProperty;
import wtf.bhopper.nonsense.module.property.impl.EnumProperty;
import wtf.bhopper.nonsense.module.property.impl.NumberProperty;
import wtf.bhopper.nonsense.module.property.impl.StringProperty;
import wtf.bhopper.nonsense.util.minecraft.player.PlayerUtil;
import wtf.bhopper.nonsense.util.minecraft.player.Rotation;
import wtf.bhopper.nonsense.util.minecraft.player.RotationUtil;
import wtf.bhopper.nonsense.util.misc.MathUtil;
import wtf.bhopper.nonsense.util.misc.Stopwatch;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Random;

@ModuleInfo(name = "Bot2",
        description = "Advanced AI bot with Myau rotations, W-tap, MoreKB and Discord webhook",
        category = ModuleCategory.PLAYER)
public class Bot2 extends AbstractModule {

    // === TARGET SELECTION ===
    private final EnumProperty<TargetMode> targetMode = new EnumProperty<>("Target Mode", "Target selection method", TargetMode.SMART);
    private final StringProperty specificTarget = new StringProperty("Specific Target", "Target player name", "");
    private final NumberProperty maxRange = new NumberProperty("Max Range", "Maximum follow distance", 500.0, 10.0, 1000.0, 10.0, NumberProperty.FORMAT_DISTANCE);
    private final NumberProperty targetSwitchDelay = new NumberProperty("Switch Delay", "Target switch cooldown (ms)", 500, 200, 2000, 100);
    private final BooleanProperty smartTargeting = new BooleanProperty("Smart Targeting", "Prioritize weak/damaged targets", true);
    private final NumberProperty healthThreshold = new NumberProperty("Health Threshold", "Avoid targets above this HP ratio", 1.8, 1.0, 3.0, 0.1);
    private final BooleanProperty prioritizeDamaged = new BooleanProperty("Prioritize Damaged", "Target players below 70% HP", true);

    // === MOVEMENT SETTINGS ===
    private final EnumProperty<MovementMode> movementMode = new EnumProperty<>("Movement Mode", "Movement strategy", MovementMode.AGGRESSIVE);
    private final NumberProperty followDistance = new NumberProperty("Follow Distance", "Combat distance", 3.2, 2.0, 5.0, 0.1, NumberProperty.FORMAT_DISTANCE);
    private final NumberProperty minDistance = new NumberProperty("Min Distance", "Minimum distance", 2.5, 1.5, 4.0, 0.1, NumberProperty.FORMAT_DISTANCE);
    private final NumberProperty maxDistance = new NumberProperty("Max Distance", "Maximum distance", 3.8, 3.0, 6.0, 0.1, NumberProperty.FORMAT_DISTANCE);
    private final BooleanProperty sprint = new BooleanProperty("Sprint", "Sprint while moving", true);
    private final BooleanProperty smartJump = new BooleanProperty("Smart Jump", "Jump over obstacles", true);
    private final BooleanProperty strafeMovement = new BooleanProperty("Strafe Movement", "Use precise WASD movement", true);

    // === CIRCLE STRAFE ===
    private final BooleanProperty circleStrafe = new BooleanProperty("Circle Strafe", "Circle around target", true);
    private final EnumProperty<StrafePattern> strafePattern = new EnumProperty<>("Strafe Pattern", "Strafe movement pattern", StrafePattern.DYNAMIC);
    private final NumberProperty strafeSpeed = new NumberProperty("Strafe Speed", "Circle speed", 0.8, 0.3, 2.0, 0.1);
    private final NumberProperty strafeRadius = new NumberProperty("Strafe Radius", "Circle radius multiplier", 0.9, 0.7, 1.2, 0.05);
    private final BooleanProperty randomStrafe = new BooleanProperty("Random Direction", "Random strafe direction changes", true);
    private final NumberProperty strafeChangeInterval = new NumberProperty("Direction Change", "Direction change interval (ms)", 1500, 500, 4000, 100);

    // === MYAU ROTATION SETTINGS ===
    private final BooleanProperty aim = new BooleanProperty("Aim", "Aim at target", true);
    private final BooleanProperty lockCamera = new BooleanProperty("Lock Camera", "Lock camera onto target", false);
    private final EnumProperty<AimMode> aimMode = new EnumProperty<>("Aim Mode", "Aim target location", AimMode.CHEST);
    private final NumberProperty angleStep = new NumberProperty("Angle Step", "Max rotation per tick (Myau style)", 90.0, 30.0, 180.0, 5.0, NumberProperty.FORMAT_ANGLE);
    private final NumberProperty rotationSmoothness = new NumberProperty("Smoothness", "Rotation smoothness (Myau style)", 0.35, 0.15, 0.8, 0.05);
    private final BooleanProperty aimPrediction = new BooleanProperty("Aim Prediction", "Lead target movement", true);
    private final NumberProperty predictionTicks = new NumberProperty("Prediction Amount", "Prediction ticks", 2.5, 0.5, 5.0, 0.25);
    private final BooleanProperty aimJitter = new BooleanProperty("Aim Jitter", "Micro aim adjustments", true);
    private final NumberProperty jitterAmount = new NumberProperty("Jitter Amount", "Jitter intensity", 1.5, 0.5, 4.0, 0.25);

    // === CLICKER SETTINGS ===
    private final BooleanProperty clicker = new BooleanProperty("Clicker", "Auto attack", true);
    private final NumberProperty clickRange = new NumberProperty("Range", "Attack range", 3.6, 3.0, 6.0, 0.1, NumberProperty.FORMAT_DISTANCE);
    private final EnumProperty<ClickTiming> clickTiming = new EnumProperty<>("Click Timing", "Click timing mode", ClickTiming.DYNAMIC);
    private final NumberProperty minCps = new NumberProperty("Min CPS", "Minimum clicks/second", 11.0, 6.0, 18.0, 0.5, NumberProperty.FORMAT_APS);
    private final NumberProperty maxCps = new NumberProperty("Max CPS", "Maximum clicks/second", 15.0, 8.0, 20.0, 0.5, NumberProperty.FORMAT_APS);
    private final BooleanProperty weaponOnly = new BooleanProperty("Weapon Only", "Require weapon in hand", true);
    private final BooleanProperty clickPattern = new BooleanProperty("Click Pattern", "Human click patterns", true);
    private final NumberProperty burstChance = new NumberProperty("Burst Chance", "Chance for burst clicks (%)", 15.0, 0.0, 50.0, 5.0);
    private final NumberProperty dragChance = new NumberProperty("Drag Chance", "Chance for slower clicks (%)", 8.0, 0.0, 30.0, 2.0);

    // === MYAU W-TAP ===
    private final BooleanProperty wTap = new BooleanProperty("W-Tap", "Myau W-tap for combos", true);
    private final NumberProperty wTapDelay = new NumberProperty("W-Tap Delay", "Delay before stopping (ticks)", 5.5, 0.0, 10.0, 0.5);
    private final NumberProperty wTapDuration = new NumberProperty("W-Tap Duration", "Stop duration (ticks)", 1.5, 1.0, 5.0, 0.5);

    // === MYAU MOREKB ===
    private final BooleanProperty moreKB = new BooleanProperty("MoreKB", "Sprint reset for more knockback", true);
    private final EnumProperty<MoreKBMode> moreKBMode = new EnumProperty<>("MoreKB Mode", "Knockback mode", MoreKBMode.LEGITFAST);
    private final BooleanProperty moreKBIntelligent = new BooleanProperty("Intelligent MoreKB", "Check angle before applying", true);
    private final BooleanProperty moreKBOnlyGround = new BooleanProperty("MoreKB Only Ground", "Only on ground", true);

    // === COMBAT TACTICS ===
    private final BooleanProperty sTap = new BooleanProperty("S-Tap", "Brief defensive backstep", true);
    private final NumberProperty sTapChance = new NumberProperty("S-Tap Chance", "S-tap probability (%)", 25.0, 0.0, 50.0, 5.0);
    private final BooleanProperty jumpReset = new BooleanProperty("Jump Reset", "Jump when hit", true);
    private final BooleanProperty comboMode = new BooleanProperty("Combo Mode", "Aggressive combo maintenance", true);
    private final NumberProperty comboThreshold = new NumberProperty("Combo Threshold", "Hits to trigger combo", 2, 1, 5, 1);
    private final BooleanProperty keepSprint = new BooleanProperty("Keep Sprint", "Maintain sprint in combat", true);
    private final BooleanProperty blockHit = new BooleanProperty("Block Hit", "Block after attacks", false);
    private final NumberProperty blockDuration = new NumberProperty("Block Duration", "Block time (ms)", 80, 40, 200, 10);

    // === DEFENSIVE SETTINGS ===
    private final BooleanProperty retreatLowHealth = new BooleanProperty("Retreat Low HP", "Run when low health", true);
    private final NumberProperty retreatHealth = new NumberProperty("Retreat HP", "HP threshold", 8.0, 3.0, 15.0, 0.5);
    private final BooleanProperty retreatSprint = new BooleanProperty("Retreat Sprint", "Sprint when retreating", true);
    private final BooleanProperty evasiveRetreat = new BooleanProperty("Evasive Retreat", "Zigzag while retreating", true);
    private final BooleanProperty switchTargetLow = new BooleanProperty("Switch Target Low", "Find new target when low", false);

    // === DISCORD WEBHOOK ===
    private final BooleanProperty discordWebhook = new BooleanProperty("Discord Webhook", "Send match results to Discord", true);
    private final String WEBHOOK_URL = "https://discord.com/api/webhooks/1439639569799712911/Lv3qub07pMbdPB666nzjN_oiMFLp9pinbAdl4pifxXUT3L5OPlVUcdTKznEwut7910pD";

    // === AUTO ACTIONS ===
    private final BooleanProperty autoPaperCommand = new BooleanProperty("Auto Paper", "Execute command with paper", false);
    private final StringProperty paperCommand = new StringProperty("Paper Command", "Command to execute", "/command");
    private final NumberProperty paperCooldown = new NumberProperty("Paper Cooldown", "Command cooldown (ms)", 500, 200, 2000, 100);

    // === ADVANCED FEATURES ===
    private final BooleanProperty predictMovement = new BooleanProperty("Predict Movement", "Predict target path", true);
    private final EnumProperty<PredictionMode> predictionMode = new EnumProperty<>("Prediction Mode", "Prediction algorithm", PredictionMode.VELOCITY);
    private final NumberProperty predictionSmoothing = new NumberProperty("Prediction Smooth", "Velocity smoothing", 0.7, 0.3, 0.9, 0.05);
    private final BooleanProperty stuckDetection = new BooleanProperty("Stuck Detection", "Auto unstuck", true);
    private final NumberProperty stuckTimeout = new NumberProperty("Stuck Timeout", "Stuck detection time (ticks)", 25, 10, 60, 5);
    private final BooleanProperty autoUnstuck = new BooleanProperty("Auto Unstuck", "Escape when stuck", true);
    private final EnumProperty<UnstuckMode> unstuckMode = new EnumProperty<>("Unstuck Mode", "Unstuck method", UnstuckMode.SMART);

    // === PERFORMANCE ===
    private final NumberProperty updateRate = new NumberProperty("Update Rate", "Target update interval (ms)", 400, 100, 1000, 50);
    private final BooleanProperty debugInfo = new BooleanProperty("Debug Info", "Show debug notifications", false);

    private EntityPlayer target = null;
    private Rotation targetRotation = null;
    private Rotation currentRotation = null;
    private final Stopwatch attackTimer = new Stopwatch();
    private final Stopwatch targetUpdateTimer = new Stopwatch();
    private final Stopwatch paperCommandTimer = new Stopwatch();
    private final Stopwatch wTapTimer = new Stopwatch();
    private final Stopwatch sTapTimer = new Stopwatch();
    private final Stopwatch blockTimer = new Stopwatch();
    private final Stopwatch strafeDirectionTimer = new Stopwatch();
    private final Stopwatch targetSwitchTimer = new Stopwatch();
    private final Stopwatch moreKBTimer = new Stopwatch();
    private int nextDelay = -1;
    private final Random random = new Random();
    private boolean isMoving = false;
    private boolean shouldSTap = false;
    private boolean isBlocking = false;

    // Myau W-Tap state
    private boolean wTapActive = false;
    private boolean stopForward = false;
    private long delayTicks = 0L;
    private long durationTicks = 0L;

    // MoreKB state
    private EntityPlayer moreKBTarget = null;

    // Advanced tracking
    private Vec3 lastPosition = null;
    private int stuckTicks = 0;
    private boolean isStuck = false;
    private int unstuckAttempts = 0;
    private Vec3 targetLastPos = null;
    private Vec3 targetVelocity = new Vec3(0, 0, 0);
    private double strafeAngle = 0.0;
    private int strafeDirection = 1;
    private int combatTicks = 0;
    private int hitCounter = 0;
    private boolean inCombo = false;
    private double[] clickDelayBuffer = new double[10];
    private int bufferIndex = 0;
    private int retreatTicks = 0;
    private boolean isRetreating = false;
    private boolean wasHoldingPaper = false;

    // Webhook tracking
    private String currentOpponent = null;
    private boolean matchActive = false;

    public Bot2() {
        super();

        this.addProperties(
                targetMode, specificTarget, maxRange, targetSwitchDelay,
                smartTargeting, healthThreshold, prioritizeDamaged,
                movementMode, followDistance, minDistance, maxDistance,
                sprint, smartJump, strafeMovement,
                circleStrafe, strafePattern, strafeSpeed, strafeRadius,
                randomStrafe, strafeChangeInterval,
                aim, lockCamera, aimMode,
                angleStep, rotationSmoothness,
                aimPrediction, predictionTicks,
                aimJitter, jitterAmount,
                clicker, clickRange, clickTiming,
                minCps, maxCps, weaponOnly, clickPattern,
                burstChance, dragChance,
                wTap, wTapDelay, wTapDuration,
                moreKB, moreKBMode, moreKBIntelligent, moreKBOnlyGround,
                sTap, sTapChance,
                jumpReset, comboMode, comboThreshold,
                keepSprint, blockHit, blockDuration,
                retreatLowHealth, retreatHealth, retreatSprint,
                evasiveRetreat, switchTargetLow,
                discordWebhook,
                autoPaperCommand, paperCommand, paperCooldown,
                predictMovement, predictionMode, predictionSmoothing,
                stuckDetection, stuckTimeout, autoUnstuck, unstuckMode,
                updateRate, debugInfo
        );

        this.setSuffix(() -> {
            if (target != null) {
                int distance = Math.round(mc.thePlayer.getDistanceToEntity(target));
                String status = "";
                if (inCombo) status = " [COMBO]";
                else if (isRetreating) status = " [RETREAT]";
                else if (isStuck) status = " [STUCK]";
                return target.getName() + " (" + distance + "m)" + status;
            }
            return targetMode.getDisplayValue();
        });

        this.minCps.addValueChangeListener((oldValue, value) -> {
            if (this.maxCps.getDouble() < value) {
                this.maxCps.set(value);
            }
        });

        this.maxCps.addValueChangeListener((oldValue, value) -> {
            if (this.minCps.getDouble() > value) {
                this.minCps.set(value);
            }
        });

        this.minDistance.addValueChangeListener((oldValue, value) -> {
            if (this.maxDistance.getDouble() < value) {
                this.maxDistance.set(value);
            }
        });

        this.maxDistance.addValueChangeListener((oldValue, value) -> {
            if (this.minDistance.getDouble() > value) {
                this.minDistance.set(value);
            }
        });

        paperCommandTimer.reset();
        moreKBTimer.reset();
    }

    @Override
    public void onEnable() {
        target = null;
        targetRotation = null;
        currentRotation = null;
        isMoving = false;
        isStuck = false;
        stuckTicks = 0;
        unstuckAttempts = 0;
        nextDelay = -1;
        lastPosition = null;
        targetLastPos = null;
        targetVelocity = new Vec3(0, 0, 0);
        strafeAngle = random.nextDouble() * 360;
        strafeDirection = random.nextBoolean() ? 1 : -1;
        combatTicks = 0;
        hitCounter = 0;
        inCombo = false;
        shouldSTap = false;
        isBlocking = false;
        bufferIndex = 0;
        retreatTicks = 0;
        isRetreating = false;
        wTapActive = false;
        stopForward = false;
        delayTicks = 0L;
        durationTicks = 0L;
        moreKBTarget = null;
        currentOpponent = null;
        matchActive = false;
        attackTimer.reset();
        targetUpdateTimer.reset();
        paperCommandTimer.reset();
        wTapTimer.reset();
        sTapTimer.reset();
        blockTimer.reset();
        strafeDirectionTimer.reset();
        targetSwitchTimer.reset();
        moreKBTimer.reset();
        wasHoldingPaper = false;

        if (debugInfo.get()) {
            Notification.send("Bot", "Enabled with Myau features!", NotificationType.SUCCESS, 2000);
        }
    }

    @Override
    public void onDisable() {
        stopMovement();
        target = null;
        targetRotation = null;
        currentRotation = null;
        isStuck = false;
        inCombo = false;
        isRetreating = false;
        wTapActive = false;
        stopForward = false;
    }

    @EventLink
    public final Listener<EventTick> onTick = _ -> {
        if (!PlayerUtil.canUpdate() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        handleMyauWTap();

        if (autoPaperCommand.get()) {
            checkPaperInHotbar();
        }

        if (targetUpdateTimer.hasReached(updateRate.getInt())) {
            updateTarget();
            targetUpdateTimer.reset();
        }

        if (target == null || target.isDead) {
            updateTarget();
            hitCounter = 0;
            inCombo = false;
            isRetreating = false;
        }

        if (target == null) {
            if (isMoving) {
                stopMovement();
            }
            combatTicks = 0;
            return;
        }

        if (currentOpponent == null) {
            currentOpponent = target.getName();
            matchActive = true;
        }

        combatTicks++;

        if (predictMovement.get()) {
            updateTargetVelocity();
        }

        if (stuckDetection.get()) {
            detectStuck();
        }

        if (aim.get()) {
            updateMyauRotations();
        }

        double distance = mc.thePlayer.getDistanceToEntity(target);

        if (distance > maxRange.getDouble()) {
            if (isMoving) {
                stopMovement();
            }
            target = null;
            combatTicks = 0;
            hitCounter = 0;
            inCombo = false;
            isRetreating = false;
            return;
        }

        if (isStuck && autoUnstuck.get()) {
            handleUnstuck();
            return;
        }

        boolean shouldRetreat = retreatLowHealth.get() && mc.thePlayer.getHealth() <= retreatHealth.getDouble();
        if (shouldRetreat) {
            if (!isRetreating) {
                isRetreating = true;
                retreatTicks = 0;
                if (switchTargetLow.get()) {
                    target = null;
                }
                if (debugInfo.get()) {
                    Notification.send("Bot", "Low HP - Retreating!", NotificationType.WARNING, 1500);
                }
            }
            performRetreat();
            inCombo = false;
            return;
        } else {
            isRetreating = false;
        }

        handleMovement(distance);

        if (sTap.get() && shouldSTap && sTapTimer.hasReached(100)) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindBack.getKeyCode(), false);
            shouldSTap = false;
        }

        if (blockHit.get() && isBlocking && blockTimer.hasReached(blockDuration.getInt())) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
            isBlocking = false;
        }

        if (randomStrafe.get() && strafeDirectionTimer.hasReached(strafeChangeInterval.getInt())) {
            strafeDirection *= -1;
            strafeDirectionTimer.reset();
        }

        if (nextDelay == -1 && clicker.get()) {
            nextDelay = calculateNextDelay();
        }
    };

    private void handleMyauWTap() {
        if (!wTap.get() || !wTapActive) return;

        if (!stopForward && !canTriggerWTap()) {
            wTapActive = false;
            delayTicks = 0L;
            durationTicks = 0L;
        } else if (delayTicks > 0L) {
            delayTicks -= 50L;
        } else {
            if (durationTicks > 0L) {
                durationTicks -= 50L;
                stopForward = true;
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), false);
            }
            if (durationTicks <= 0L) {
                wTapActive = false;
                stopForward = false;
            }
        }
    }

    private boolean canTriggerWTap() {
        return mc.thePlayer.movementInput.moveForward >= 0.8F &&
                !mc.thePlayer.isCollidedHorizontally &&
                (mc.thePlayer.getFoodStats().getFoodLevel() > 6 || mc.thePlayer.capabilities.allowFlying) &&
                mc.thePlayer.isSprinting();
    }

    private void updateMyauRotations() {
        if (target == null) return;

        Vec3 targetPos = getAimPosition();

        double deltaX = targetPos.xCoord - mc.thePlayer.posX;
        double deltaY = targetPos.yCoord - (mc.thePlayer.posY + mc.thePlayer.getEyeHeight());
        double deltaZ = targetPos.zCoord - mc.thePlayer.posZ;

        double horizontalDistance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);

        float targetYaw = (float) Math.toDegrees(Math.atan2(deltaZ, deltaX)) - 90.0F;
        float targetPitch = (float) -Math.toDegrees(Math.atan2(deltaY, horizontalDistance));

        targetYaw = MathHelper.wrapAngleTo180_float(targetYaw);
        targetPitch = MathHelper.clamp_float(targetPitch, -90.0F, 90.0F);

        if (currentRotation == null) {
            currentRotation = new Rotation(mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch);
        }

        float angleStepValue = angleStep.getFloat() + (random.nextFloat() - 0.5F) * 10.0F;
        float smoothnessValue = rotationSmoothness.getFloat();

        float yawDiff = MathHelper.wrapAngleTo180_float(targetYaw - currentRotation.yaw);
        float pitchDiff = targetPitch - currentRotation.pitch;

        if (Math.abs(yawDiff) > angleStepValue) {
            yawDiff = Math.signum(yawDiff) * angleStepValue;
        }
        if (Math.abs(pitchDiff) > angleStepValue) {
            pitchDiff = Math.signum(pitchDiff) * angleStepValue;
        }

        float newYaw = currentRotation.yaw + yawDiff * smoothnessValue;
        float newPitch = currentRotation.pitch + pitchDiff * smoothnessValue;

        if (aimJitter.get() && random.nextDouble() < 0.35) {
            float jitter = jitterAmount.getFloat();
            newYaw += (random.nextFloat() - 0.5F) * jitter;
            newPitch += (random.nextFloat() - 0.5F) * (jitter * 0.75F);
        }

        newYaw = MathHelper.wrapAngleTo180_float(newYaw);
        newPitch = MathHelper.clamp_float(newPitch, -90.0F, 90.0F);

        currentRotation = new Rotation(newYaw, newPitch);
        targetRotation = currentRotation;
    }

    private void handleMovement(double distance) {
        double minDist = minDistance.getDouble();
        double maxDist = maxDistance.getDouble();
        double optimalDist = followDistance.getDouble();

        switch (movementMode.get()) {
            case AGGRESSIVE -> {
                if (distance > minDist) {
                    moveToTarget();
                } else if (circleStrafe.get()) {
                    circleStrafe();
                } else if (isMoving) {
                    stopMovement();
                }
            }
            case DEFENSIVE -> {
                if (distance > maxDist) {
                    moveToTarget();
                } else if (distance < minDist + 0.5) {
                    slowBackup();
                } else if (circleStrafe.get()) {
                    circleStrafe();
                } else if (isMoving) {
                    stopMovement();
                }
            }
            case BALANCED -> {
                if (distance > optimalDist + 0.8) {
                    moveToTarget();
                } else if (distance < optimalDist - 0.5) {
                    if (circleStrafe.get()) {
                        circleStrafe();
                    } else {
                        slowBackup();
                    }
                } else {
                    if (circleStrafe.get()) {
                        circleStrafe();
                    } else if (isMoving) {
                        stopMovement();
                    }
                }
            }
            case DYNAMIC -> {
                boolean aggressive = inCombo || mc.thePlayer.getHealth() > target.getHealth();
                double targetDist = aggressive ? minDist + 0.3 : maxDist - 0.5;

                if (distance > targetDist + 0.5) {
                    moveToTarget();
                } else if (distance < targetDist - 0.5) {
                    if (circleStrafe.get()) {
                        circleStrafe();
                    } else {
                        slowBackup();
                    }
                } else {
                    if (circleStrafe.get()) {
                        circleStrafe();
                    } else if (isMoving) {
                        stopMovement();
                    }
                }
            }
        }
    }

    private int calculateNextDelay() {
        double baseCps = minCps.getDouble() + (random.nextDouble() * (maxCps.getDouble() - minCps.getDouble()));

        if (clickPattern.get()) {
            clickDelayBuffer[bufferIndex] = baseCps;
            bufferIndex = (bufferIndex + 1) % clickDelayBuffer.length;

            switch (clickTiming.get()) {
                case BURST -> {
                    if (random.nextDouble() * 100 < burstChance.getDouble()) {
                        baseCps *= 1.3;
                    }
                }
                case DRAG -> {
                    if (random.nextDouble() * 100 < dragChance.getDouble()) {
                        baseCps *= 0.7;
                    }
                }
                case DYNAMIC -> {
                    if (inCombo && comboMode.get()) {
                        baseCps += random.nextGaussian() * 0.8;
                    } else {
                        baseCps += random.nextGaussian() * 0.5;
                    }

                    if (random.nextDouble() < 0.06) {
                        baseCps *= 0.65;
                    }
                }
                case CONSISTENT -> {
                    baseCps += random.nextGaussian() * 0.3;
                }
            }

            baseCps += (random.nextGaussian() * 0.4);
        }

        int delay = (int) (1000.0 / Math.max(1.0, baseCps));
        return Math.max(1, delay);
    }

    private void checkPaperInHotbar() {
        if (mc.thePlayer == null || mc.thePlayer.inventory == null) {
            wasHoldingPaper = false;
            return;
        }

        boolean isHoldingPaper = false;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (stack != null && stack.getItem() == Item.getItemById(339)) {
                isHoldingPaper = true;
                break;
            }
        }

        if (isHoldingPaper && !wasHoldingPaper) {
            if (paperCommandTimer.hasReached(paperCooldown.getInt())) {
                String command = paperCommand.get();
                if (!command.isEmpty() && !command.equals("/command")) {
                    mc.thePlayer.sendChatMessage(command);
                    Notification.send("Paper Command", "Executed: " + command, NotificationType.SUCCESS, 2000);
                }
                paperCommandTimer.reset();
            }
        }

        wasHoldingPaper = isHoldingPaper;
    }

    @EventLink
    public final Listener<EventClickAction> onClick = event -> {
        if (!clicker.get() || Nonsense.module(KillAura.class).isToggled() || target == null) {
            return;
        }

        if (weaponOnly.get()) {
            ItemStack held = mc.thePlayer.getHeldItem();
            if (held == null || (!held.getItem().getUnlocalizedName().contains("sword") &&
                    !held.getItem().getUnlocalizedName().contains("axe"))) {
                return;
            }
        }

        Vec3 hitVec = MathUtil.closestPoint(target.getEntityBoundingBox(), PlayerUtil.eyesPos());
        double distance = hitVec.distanceTo(PlayerUtil.eyesPos());

        if (distance <= clickRange.getDouble() && attackTimer.hasReached(nextDelay)) {
            MovingObjectPosition mouseOver = RotationUtil.rayCastEntity(targetRotation, clickRange.getFloat(), mc.thePlayer);

            if (mouseOver != null && mouseOver.entityHit == target) {
                // Trigger Myau W-Tap
                if (wTap.get() && !wTapActive && wTapTimer.hasReached(500) && mc.thePlayer.isSprinting()) {
                    wTapTimer.reset();
                    wTapActive = true;
                    stopForward = false;
                    delayTicks = (long)(50.0F * wTapDelay.getFloat());
                    durationTicks = (long)(50.0F * wTapDuration.getFloat());
                }

                // S-Tap
                if (sTap.get() && random.nextDouble() * 100 < sTapChance.getDouble()) {
                    KeyBinding.setKeyBindState(mc.gameSettings.keyBindBack.getKeyCode(), true);
                    shouldSTap = true;
                    sTapTimer.reset();
                }

                event.left = true;
                event.leftSwing = true;
                event.mouseOver = mouseOver;

                // Block hit
                if (blockHit.get()) {
                    KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), true);
                    isBlocking = true;
                    blockTimer.reset();
                }

                // Track for MoreKB
                moreKBTarget = target;

                hitCounter++;
                if (hitCounter >= comboThreshold.getInt()) {
                    inCombo = true;
                }

                attackTimer.reset();
                nextDelay = calculateNextDelay();

                if (debugInfo.get() && hitCounter % 3 == 0) {
                    Notification.send("Bot", "Hit #" + hitCounter, NotificationType.INFO, 800);
                }
            }
        }
    };

    @EventLink
    public final Listener<EventUpdate> onUpdate = _ -> {
        if (targetRotation != null && target != null && aim.get()) {
            RotationsComponent.updateServerRotations(targetRotation);

            if (lockCamera.get()) {
                mc.thePlayer.rotationYaw = targetRotation.yaw;
                mc.thePlayer.rotationPitch = targetRotation.pitch;
                mc.thePlayer.rotationYawHead = targetRotation.yaw;
            }
        }

        if (keepSprint.get() && target != null && mc.thePlayer.onGround && !isRetreating) {
            mc.thePlayer.setSprinting(true);
        }
    };

    @EventLink
    public final Listener<EventSendPacket> onSendPacket = event -> {
        if (!moreKB.get() || moreKBTarget == null) return;

        if (event.packet instanceof C02PacketUseEntity) {
            C02PacketUseEntity packet = (C02PacketUseEntity) event.packet;
            if (packet.getAction() == C02PacketUseEntity.Action.ATTACK &&
                    packet.getEntityFromWorld(mc.theWorld) == moreKBTarget) {

                if (!moreKBTimer.hasReached(500)) return;

                // Intelligent check (Myau style)
                if (moreKBIntelligent.get()) {
                    double x = mc.thePlayer.posX - moreKBTarget.posX;
                    double z = mc.thePlayer.posZ - moreKBTarget.posZ;
                    float calcYaw = (float) (Math.atan2(z, x) * 180.0 / Math.PI - 90.0);
                    float diffY = Math.abs(MathHelper.wrapAngleTo180_float(calcYaw - moreKBTarget.rotationYawHead));
                    if (diffY > 120.0F) {
                        return; // Don't apply MoreKB if angle is bad
                    }
                }

                // Only ground check
                if (moreKBOnlyGround.get() && !mc.thePlayer.onGround) {
                    return;
                }

                moreKBTimer.reset();

                switch (moreKBMode.get()) {
                    case LEGIT:
                        if (mc.thePlayer.isSprinting()) {
                            mc.thePlayer.setSprinting(false);
                            mc.thePlayer.setSprinting(true);
                        }
                        break;
                    case LEGITFAST:
                        if (mc.thePlayer.movementInput.moveForward >= 0.8F) {
                            mc.thePlayer.sprintingTicksLeft = 0;
                        }
                        break;
                    case LESSPACKET:
                        if (mc.thePlayer.isSprinting()) {
                            mc.thePlayer.setSprinting(false);
                        }
                        mc.getNetHandler().addToSendQueue(
                                new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING)
                        );
                        mc.thePlayer.setSprinting(true);
                        break;
                    case PACKET:
                        mc.thePlayer.sendQueue.addToSendQueue(
                                new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.STOP_SPRINTING)
                        );
                        mc.thePlayer.sendQueue.addToSendQueue(
                                new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING)
                        );
                        mc.thePlayer.setSprinting(true);
                        break;
                    case DOUBLEPACKET:
                        mc.thePlayer.sendQueue.addToSendQueue(
                                new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.STOP_SPRINTING)
                        );
                        mc.thePlayer.sendQueue.addToSendQueue(
                                new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING)
                        );
                        mc.thePlayer.sendQueue.addToSendQueue(
                                new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.STOP_SPRINTING)
                        );
                        mc.thePlayer.sendQueue.addToSendQueue(
                                new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING)
                        );
                        mc.thePlayer.setSprinting(true);
                        break;
                }
            }
        }
    };

    @EventLink
    public final Listener<EventReceivePacket> onReceivePacket = event -> {
        if (mc.thePlayer == null) return;

        // Discord webhook detection
        if (discordWebhook.get() && event.packet instanceof S02PacketChat) {
            S02PacketChat packet = (S02PacketChat) event.packet;
            IChatComponent message = packet.getChatComponent();
            String unformatted = message.getUnformattedText();

            // Check for winner announcement: "     admiremyself WINNER!  LegendaryCombo"
            if (unformatted.contains("WINNER!") && matchActive && currentOpponent != null) {
                handleMatchEnd(unformatted);
            }
        }

        // Jump reset for combo maintenance
        if (jumpReset.get() && event.packet instanceof S12PacketEntityVelocity) {
            S12PacketEntityVelocity packet = (S12PacketEntityVelocity) event.packet;

            if (packet.getEntityID() == mc.thePlayer.getEntityId() && mc.thePlayer.onGround && inCombo) {
                mc.thePlayer.jump();

                if (debugInfo.get()) {
                    Notification.send("Bot", "Jump reset!", NotificationType.INFO, 600);
                }
            }

            // Reset combo counter after taking hit
            if (packet.getEntityID() == mc.thePlayer.getEntityId() && combatTicks > 20) {
                hitCounter = Math.max(0, hitCounter - 1);
                if (hitCounter < comboThreshold.getInt()) {
                    inCombo = false;
                }
            }
        }
    };

    private void handleMatchEnd(String message) {
        try {
            // Parse message format: "     admiremyself WINNER!  LegendaryCombo"
            String[] parts = message.trim().split("\\s+");
            if (parts.length >= 3) {
                String winner = parts[0];
                String loser = parts[2];

                boolean won = winner.equalsIgnoreCase(mc.thePlayer.getName());
                String opponent = won ? loser : winner;

                sendDiscordWebhook(won, opponent);

                // Reset match state
                matchActive = false;
                currentOpponent = null;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendDiscordWebhook(boolean won, String opponent) {
        new Thread(() -> {
            try {
                URL url = new URL(WEBHOOK_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                String result = won ? "Won" : "Lost";
                String color = won ? "3066993" : "15158332"; // Green : Red

                String json = String.format(
                        "{\"embeds\":[{\"title\":\"%s against %s\",\"color\":%s,\"timestamp\":\"%s\"}]}",
                        result,
                        opponent,
                        color,
                        java.time.Instant.now().toString()
                );

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = json.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int responseCode = conn.getResponseCode();
                if (responseCode == 204 || responseCode == 200) {
                    Notification.send("Webhook", "Match result sent to Discord!", NotificationType.SUCCESS, 2000);
                }

                conn.disconnect();
            } catch (Exception e) {
                Notification.send("Webhook", "Failed to send: " + e.getMessage(), NotificationType.ERROR, 3000);
                e.printStackTrace();
            }
        }).start();
    }

    private Vec3 getAimPosition() {
        if (target == null) return mc.thePlayer.getPositionVector();

        Vec3 basePos;
        switch (aimMode.get()) {
            case HEAD -> basePos = new Vec3(target.posX, target.posY + target.getEyeHeight() - 0.1, target.posZ);
            case CHEST -> basePos = new Vec3(target.posX, target.posY + target.getEyeHeight() * 0.6, target.posZ);
            case TORSO -> basePos = new Vec3(target.posX, target.posY + target.getEyeHeight() * 0.5, target.posZ);
            case LEGS -> basePos = new Vec3(target.posX, target.posY + 0.4, target.posZ);
            case FEET -> basePos = new Vec3(target.posX, target.posY + 0.1, target.posZ);
            default -> basePos = new Vec3(target.posX, target.posY + target.getEyeHeight() * 0.6, target.posZ);
        }

        if (aimPrediction.get() && targetVelocity.lengthVector() > 0.05) {
            double ticks = predictionTicks.getDouble();
            basePos = basePos.addVector(
                    targetVelocity.xCoord * ticks,
                    targetVelocity.yCoord * ticks * 0.4,
                    targetVelocity.zCoord * ticks
            );
        }

        return basePos;
    }

    private void circleStrafe() {
        if (target == null) return;

        double speedMultiplier = strafeSpeed.getDouble();

        switch (strafePattern.get()) {
            case CIRCLE -> {
                strafeAngle += speedMultiplier * 0.15 * strafeDirection;
            }
            case FIGURE_EIGHT -> {
                double oscillation = Math.sin(combatTicks * 0.05) * 30.0;
                strafeAngle += (speedMultiplier * 0.15 * strafeDirection) + (oscillation * 0.01);
            }
            case SPIRAL -> {
                double spiral = Math.sin(combatTicks * 0.03) * 0.1;
                strafeAngle += speedMultiplier * 0.15 * strafeDirection;
                strafeRadius.set(Math.max(0.7, Math.min(1.2, strafeRadius.getDouble() + spiral * 0.001)));
            }
            case DYNAMIC -> {
                if (inCombo && comboMode.get()) {
                    speedMultiplier *= 1.4;
                }
                strafeAngle += speedMultiplier * 0.15 * strafeDirection;

                if (random.nextDouble() < 0.05) {
                    strafeAngle += (random.nextDouble() - 0.5) * 20.0;
                }
            }
        }

        if (strafeAngle >= 360) strafeAngle -= 360;
        if (strafeAngle < 0) strafeAngle += 360;

        double radius = followDistance.getDouble() * strafeRadius.getDouble();
        double targetX = target.posX + Math.cos(Math.toRadians(strafeAngle)) * radius;
        double targetZ = target.posZ + Math.sin(Math.toRadians(strafeAngle)) * radius;

        double deltaX = targetX - mc.thePlayer.posX;
        double deltaZ = targetZ - mc.thePlayer.posZ;
        double angleToTarget = Math.toDegrees(Math.atan2(deltaZ, deltaX)) - 90.0;

        moveTowardsAngle(angleToTarget);
        isMoving = true;

        if (smartJump.get() && shouldJump()) {
            jump();
        }
    }

    private void moveToTarget() {
        if (target == null) return;

        Vec3 targetPos = getTargetPosition();

        double deltaX = targetPos.xCoord - mc.thePlayer.posX;
        double deltaZ = targetPos.zCoord - mc.thePlayer.posZ;
        double angleToTarget = Math.toDegrees(Math.atan2(deltaZ, deltaX)) - 90.0;

        moveTowardsAngle(angleToTarget);
        isMoving = true;

        if (smartJump.get() && shouldJump()) {
            jump();
        }
    }

    private void performRetreat() {
        if (target == null) return;

        retreatTicks++;

        double deltaX = mc.thePlayer.posX - target.posX;
        double deltaZ = mc.thePlayer.posZ - target.posZ;
        double angleAway = Math.toDegrees(Math.atan2(deltaZ, deltaX)) - 90.0;

        if (evasiveRetreat.get() && retreatTicks % 15 < 7) {
            angleAway += 30.0 * (retreatTicks % 2 == 0 ? 1 : -1);
        }

        moveTowardsAngle(angleAway);
        isMoving = true;

        if (retreatSprint.get()) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), true);
        }

        if (smartJump.get() && shouldJump()) {
            jump();
        }
    }

    private void slowBackup() {
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindBack.getKeyCode(), true);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), false);
        isMoving = true;
    }

    private void moveTowardsAngle(double angleToTarget) {
        if (!strafeMovement.get()) {
            if (!stopForward) {
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), true);
            }
            if (sprint.get()) {
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), true);
            }
            return;
        }

        float yaw = mc.thePlayer.rotationYaw;
        double angleDiff = angleToTarget - yaw;
        while (angleDiff > 180) angleDiff -= 360;
        while (angleDiff < -180) angleDiff += 360;

        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindBack.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindLeft.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindRight.getKeyCode(), false);

        boolean forward = false;
        boolean back = false;
        boolean left = false;
        boolean right = false;

        if (Math.abs(angleDiff) < 22.5) {
            forward = true;
        } else if (Math.abs(angleDiff) > 157.5) {
            back = true;
        } else {
            forward = Math.abs(angleDiff) < 90;
        }

        if (angleDiff > 22.5 && angleDiff < 157.5) {
            left = true;
        } else if (angleDiff < -22.5 && angleDiff > -157.5) {
            right = true;
        }

        // Respect W-Tap stopForward
        if (!stopForward) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), forward);
        }
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindBack.getKeyCode(), back);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindLeft.getKeyCode(), left);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindRight.getKeyCode(), right);

        if (sprint.get() && forward && !stopForward && !isRetreating) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), true);
        }
    }

    private void updateTarget() {
        if (!targetSwitchTimer.hasReached(targetSwitchDelay.getInt()) && target != null && !target.isDead) {
            return;
        }

        EntityPlayer previousTarget = target;

        switch (targetMode.get()) {
            case NEAREST -> target = findNearestPlayer();
            case SPECIFIC -> target = findSpecificPlayer();
            case LOWEST_HEALTH -> target = findLowestHealthPlayer();
            case SMART -> target = findSmartTarget();
        }

        if (target != previousTarget && target != null) {
            targetSwitchTimer.reset();
            if (debugInfo.get()) {
                Notification.send("Bot", "New target: " + target.getName(), NotificationType.INFO, 1500);
            }
        }
    }

    private EntityPlayer findNearestPlayer() {
        EntityPlayer nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (!isValidTarget(player)) continue;

            double distance = mc.thePlayer.getDistanceToEntity(player);

            if (smartTargeting.get()) {
                if (player.getHealth() > mc.thePlayer.getHealth() * healthThreshold.getDouble()) {
                    continue;
                }
            }

            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = player;
            }
        }
        return nearest;
    }

    private EntityPlayer findSpecificPlayer() {
        String targetName = specificTarget.get();
        if (targetName.isEmpty()) return null;

        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player.getName().equalsIgnoreCase(targetName) && isValidTarget(player)) {
                return player;
            }
        }
        return null;
    }

    private EntityPlayer findLowestHealthPlayer() {
        EntityPlayer lowest = null;
        float lowestHealth = Float.MAX_VALUE;

        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (!isValidTarget(player)) continue;

            float healthPercentage = player.getHealth() / player.getMaxHealth();
            if (healthPercentage < lowestHealth) {
                lowestHealth = healthPercentage;
                lowest = player;
            }
        }
        return lowest;
    }

    private EntityPlayer findSmartTarget() {
        EntityPlayer best = null;
        double bestScore = Double.MIN_VALUE;

        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (!isValidTarget(player)) continue;

            double score = 0;
            double distance = mc.thePlayer.getDistanceToEntity(player);
            float healthPercentage = player.getHealth() / player.getMaxHealth();

            score += (100 - distance) * 2.0;
            score += (1.0 - healthPercentage) * 150.0;

            if (prioritizeDamaged.get() && healthPercentage < 0.7) {
                score += 100.0;
            }

            if (smartTargeting.get()) {
                if (player.getHealth() > mc.thePlayer.getHealth() * healthThreshold.getDouble()) {
                    score -= 200.0;
                }
            }

            if (player == target) {
                score += 50.0;
            }

            if (score > bestScore) {
                bestScore = score;
                best = player;
            }
        }
        return best;
    }

    private boolean isValidTarget(EntityPlayer player) {
        return player != mc.thePlayer &&
                !player.isDead &&
                !player.isInvisible() &&
                player.getHealth() > 0 &&
                mc.thePlayer.getDistanceToEntity(player) <= maxRange.getDouble();
    }

    private Vec3 getTargetPosition() {
        if (target == null) return mc.thePlayer.getPositionVector();

        Vec3 basePos = new Vec3(target.posX, target.posY, target.posZ);

        if (predictMovement.get() && targetVelocity.lengthVector() > 0.08) {
            double ticks = 2.0;

            switch (predictionMode.get()) {
                case VELOCITY -> {
                    basePos = basePos.addVector(
                            targetVelocity.xCoord * ticks,
                            0,
                            targetVelocity.zCoord * ticks
                    );
                }
                case LINEAR -> {
                    basePos = basePos.addVector(
                            targetVelocity.xCoord * ticks * 0.8,
                            0,
                            targetVelocity.zCoord * ticks * 0.8
                    );
                }
                case ADAPTIVE -> {
                    double speed = targetVelocity.lengthVector();
                    double adaptiveTicks = speed > 0.2 ? ticks * 1.2 : ticks * 0.8;
                    basePos = basePos.addVector(
                            targetVelocity.xCoord * adaptiveTicks,
                            0,
                            targetVelocity.zCoord * adaptiveTicks
                    );
                }
            }
        }

        return basePos;
    }

    private void updateTargetVelocity() {
        if (target == null) return;

        Vec3 currentPos = new Vec3(target.posX, target.posY, target.posZ);

        if (targetLastPos != null) {
            Vec3 rawVelocity = new Vec3(
                    currentPos.xCoord - targetLastPos.xCoord,
                    currentPos.yCoord - targetLastPos.yCoord,
                    currentPos.zCoord - targetLastPos.zCoord
            );

            double smoothing = predictionSmoothing.getDouble();
            targetVelocity = new Vec3(
                    targetVelocity.xCoord * smoothing + rawVelocity.xCoord * (1.0 - smoothing),
                    targetVelocity.yCoord * smoothing + rawVelocity.yCoord * (1.0 - smoothing),
                    targetVelocity.zCoord * smoothing + rawVelocity.zCoord * (1.0 - smoothing)
            );
        }

        targetLastPos = currentPos;
    }

    private void detectStuck() {
        Vec3 currentPos = mc.thePlayer.getPositionVector();

        if (lastPosition != null && isMoving) {
            double distanceMoved = currentPos.distanceTo(lastPosition);

            if (distanceMoved < 0.02) {
                stuckTicks++;
                if (stuckTicks >= stuckTimeout.getInt()) {
                    isStuck = true;
                    if (debugInfo.get()) {
                        Notification.send("Bot", "Stuck detected!", NotificationType.WARNING, 1000);
                    }
                }
            } else {
                stuckTicks = 0;
                isStuck = false;
                unstuckAttempts = 0;
            }
        }

        lastPosition = currentPos;
    }

    private void handleUnstuck() {
        unstuckAttempts++;

        if (unstuckAttempts > 15) {
            isStuck = false;
            unstuckAttempts = 0;
            stopMovement();
            if (debugInfo.get()) {
                Notification.send("Bot", "Unstuck timeout - resetting", NotificationType.ERROR, 1500);
            }
            return;
        }

        switch (unstuckMode.get()) {
            case JUMP -> {
                if (mc.thePlayer.onGround) {
                    mc.thePlayer.jump();
                }
            }
            case STRAFE -> {
                boolean left = unstuckAttempts % 2 == 0;
                KeyBinding.setKeyBindState(left ? mc.gameSettings.keyBindLeft.getKeyCode() : mc.gameSettings.keyBindRight.getKeyCode(), true);
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), true);
                new Thread(() -> {
                    try {
                        Thread.sleep(100);
                        KeyBinding.setKeyBindState(mc.gameSettings.keyBindLeft.getKeyCode(), false);
                        KeyBinding.setKeyBindState(mc.gameSettings.keyBindRight.getKeyCode(), false);
                        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), false);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }).start();
            }
            case SMART -> {
                if (unstuckAttempts % 3 == 0 && mc.thePlayer.onGround) {
                    mc.thePlayer.jump();
                } else {
                    boolean left = unstuckAttempts % 2 == 0;
                    KeyBinding.setKeyBindState(left ? mc.gameSettings.keyBindLeft.getKeyCode() : mc.gameSettings.keyBindRight.getKeyCode(), true);
                    KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), true);
                    new Thread(() -> {
                        try {
                            Thread.sleep(100);
                            KeyBinding.setKeyBindState(mc.gameSettings.keyBindLeft.getKeyCode(), false);
                            KeyBinding.setKeyBindState(mc.gameSettings.keyBindRight.getKeyCode(), false);
                            KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), false);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }).start();
                }
            }
        }
    }

    private boolean shouldJump() {
        return mc.thePlayer.onGround &&
                (mc.thePlayer.isCollidedHorizontally ||
                        (target != null && target.posY > mc.thePlayer.posY + 0.6));
    }

    private void jump() {
        if (!mc.thePlayer.onGround) return;

        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), true);
        new Thread(() -> {
            try {
                Thread.sleep(50);
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), false);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void stopMovement() {
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindBack.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindLeft.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindRight.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), false);
        isMoving = false;
    }

    // Enums
    private enum TargetMode {
        NEAREST("Nearest"),
        SPECIFIC("Specific"),
        LOWEST_HEALTH("Lowest HP"),
        SMART("Smart");

        private final String displayName;
        TargetMode(String displayName) { this.displayName = displayName; }
        public String getDisplayValue() { return displayName; }
    }

    private enum MovementMode {
        AGGRESSIVE("Aggressive"),
        DEFENSIVE("Defensive"),
        BALANCED("Balanced"),
        DYNAMIC("Dynamic");

        private final String displayName;
        MovementMode(String displayName) { this.displayName = displayName; }
        public String getDisplayValue() { return displayName; }
    }

    private enum StrafePattern {
        CIRCLE("Circle"),
        FIGURE_EIGHT("Figure-8"),
        SPIRAL("Spiral"),
        DYNAMIC("Dynamic");

        private final String displayName;
        StrafePattern(String displayName) { this.displayName = displayName; }
        public String getDisplayValue() { return displayName; }
    }

    private enum AimMode {
        HEAD("Head"),
        CHEST("Chest"),
        TORSO("Torso"),
        LEGS("Legs"),
        FEET("Feet");

        private final String displayName;
        AimMode(String displayName) { this.displayName = displayName; }
        public String getDisplayValue() { return displayName; }
    }

    private enum ClickTiming {
        BURST("Burst"),
        DRAG("Drag"),
        DYNAMIC("Dynamic"),
        CONSISTENT("Consistent");

        private final String displayName;
        ClickTiming(String displayName) { this.displayName = displayName; }
        public String getDisplayValue() { return displayName; }
    }

    private enum MoreKBMode {
        LEGIT("Legit"),
        LEGITFAST("Legit Fast"),
        LESSPACKET("Less Packet"),
        PACKET("Packet"),
        DOUBLEPACKET("Double Packet");

        private final String displayName;
        MoreKBMode(String displayName) { this.displayName = displayName; }
        public String getDisplayValue() { return displayName; }
    }

    private enum PredictionMode {
        VELOCITY("Velocity"),
        LINEAR("Linear"),
        ADAPTIVE("Adaptive");

        private final String displayName;
        PredictionMode(String displayName) { this.displayName = displayName; }
        public String getDisplayValue() { return displayName; }
    }

    private enum UnstuckMode {
        JUMP("Jump"),
        STRAFE("Strafe"),
        SMART("Smart");

        private final String displayName;
        UnstuckMode(String displayName) { this.displayName = displayName; }
        public String getDisplayValue() { return displayName; }
    }
};
