package thunder.hack.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.DamageUtil;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.RaycastContext;
import thunder.hack.core.Managers;
import thunder.hack.events.impl.EventTick;
import thunder.hack.events.impl.PacketEvent;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.setting.impl.ColorSetting;
import thunder.hack.utility.Timer;
import thunder.hack.utility.player.InteractionUtility;
import thunder.hack.utility.player.InventoryUtility;
import thunder.hack.utility.render.Render2DEngine;
import thunder.hack.utility.render.Render3DEngine;
import thunder.hack.utility.world.ExplosionUtility;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AnchorAura - explodes respawn anchors to kill players. Does not work in the nether.
 * Logic ported from Mio client (AnchorAuraModule) and adapted to the ThunderHack API.
 */
public final class AnchorAura extends Module {
    private static final double POWER = 5.0;

    private final Setting<Integer> delay = new Setting<>("Delay", 50, 0, 1000);
    private final Setting<Float> range = new Setting<>("Range", 5.0f, 0.0f, 6.0f);
    private final Setting<Float> wallRange = new Setting<>("WallRange", 5.0f, 0.0f, 6.0f);
    private final Setting<Float> targetRange = new Setting<>("TargetRange", 6.0f, 1.0f, 16.0f);
    private final Setting<Float> minDamage = new Setting<>("MinDamage", 6.0f, 0.1f, 20.0f);
    private final Setting<Float> maxSelfDamage = new Setting<>("MaxSelfDamage", 12.0f, 0.0f, 20.0f);
    private final Setting<Boolean> antiSuicide = new Setting<>("AntiSuicide", true);
    private final Setting<Boolean> rotate = new Setting<>("Rotate", false);
    private final Setting<Boolean> airPlace = new Setting<>("AirPlace", false);
    private final Setting<Boolean> liquidPlace = new Setting<>("LiquidPlace", false);
    private final Setting<Boolean> inhibit = new Setting<>("Inhibit", true);
    private final Setting<Boolean> sequential = new Setting<>("Sequential", true);

    private final Setting<Boolean> render = new Setting<>("Render", true);
    private final Setting<ColorSetting> fill = new Setting<>("Fill", new ColorSetting(new Color(189, 153, 255, 67)), v -> render.getValue());
    private final Setting<ColorSetting> outline = new Setting<>("Outline", new ColorSetting(new Color(189, 153, 255, 201)), v -> render.getValue());
    private final Setting<Boolean> fade = new Setting<>("Fade", true, v -> render.getValue());
    private final Setting<Float> fadeTime = new Setting<>("FadeTime", 1.0f, 0.1f, 1.0f, v -> render.getValue() && fade.getValue());

    private final Setting<Boolean> mining = new Setting<>("Mining", false);
    private final Setting<Boolean> eating = new Setting<>("Eating", false);
    private final Setting<Float> health = new Setting<>("Health", 4.0f, 0.0f, 36.0f);

    public static PlayerEntity target;

    private BlockPos bestPos;
    private Direction bestDir;
    private PlayerEntity bestPlayer;
    private float bestDamage;

    private final Timer delayTimer = new Timer();
    private final Map<BlockPos, Long> renderPositions = new ConcurrentHashMap<>();

    public AnchorAura() {
        super("AnchorAura", Category.COMBAT);
    }

    /* ------------------------------------------------------------------ */
    /*  lifecycle                                                         */
    /* ------------------------------------------------------------------ */

    @Override
    public void onEnable() {
        reset();
    }

    @Override
    public void onDisable() {
        reset();
    }

    private void reset() {
        bestPos = null;
        bestDir = null;
        bestPlayer = null;
        bestDamage = 0f;
        target = null;
        renderPositions.clear();
    }

    @Override
    public String getDisplayInfo() {
        if (bestPlayer == null)
            return null;
        return bestPlayer.getName().getString() + ", " + String.format("%.1f", bestDamage);
    }

    /** Best damage the module currently has available (used by AutoCrystal to decide who goes first). */
    public float getBestDamage() {
        return bestPlayer == null ? 0f : bestDamage;
    }

    /* ------------------------------------------------------------------ */
    /*  main loop                                                         */
    /* ------------------------------------------------------------------ */

    @EventHandler
    public void onTick(EventTick e) {
        if (fullNullCheck()) return;

        if (mc.world.getDimension().respawnAnchorWorks()) {
            sendMessage("Doesn't work in the nether!");
            disable();
            return;
        }

        refreshBest();

        if (shouldPause()) return;
        if (bestPos == null || bestDir == null) return;

        Hand anchorHand = getAnchorHand();
        BlockPos pos = bestPos;
        BlockState state = mc.world.getBlockState(pos);
        int prevSlot = mc.player.getInventory().selectedSlot;

        if (state.isOf(Blocks.RESPAWN_ANCHOR)) {
            Direction clickDir = getClickDirection(pos);
            if (clickDir == null) return;

            if (!delayTimer.passedMs(delay.getValue() + Managers.SERVER.getPing())) return;

            if (state.get(RespawnAnchorBlock.CHARGES) == 0)
                chargeAnchor(pos, clickDir, prevSlot);

            Vec3d center = Vec3d.ofCenter(pos);
            if (rotate.getValue())
                rotateTo(center);

            clickBlock(pos, clickDir, center, Hand.MAIN_HAND, Hand.OFF_HAND);

            if (inhibit.getValue())
                mc.world.setBlockState(pos, Blocks.AIR.getDefaultState());
        }

        placeAnchor(pos, bestDir, anchorHand);
    }

    @EventHandler
    public void onPacketReceive(PacketEvent.Receive e) {
        if (fullNullCheck() || !sequential.getValue()) return;
        if (bestPos == null || bestPlayer == null) return;

        if (e.getPacket() instanceof BlockUpdateS2CPacket packet
                && packet.getState().isAir()
                && packet.getPos().equals(bestPos)) {
            final BlockPos pos = bestPos;
            final Direction dir = bestDir;
            mc.execute(() -> {
                if (fullNullCheck() || dir == null || shouldPause()) return;
                placeAnchor(pos, dir, getAnchorHand());
            });
        }
    }

    /* ------------------------------------------------------------------ */
    /*  search                                                            */
    /* ------------------------------------------------------------------ */

    private record Candidate(BlockPos pos, Direction dir, PlayerEntity player, float damage) {
    }

    private void refreshBest() {
        Candidate c = scan();
        if (c == null) {
            bestPos = null;
            bestDir = null;
            bestPlayer = null;
            bestDamage = 0f;
            target = null;
            return;
        }
        bestPos = c.pos();
        bestDir = c.dir();
        bestPlayer = c.player();
        bestDamage = c.damage();
        target = c.player();
    }

    private Candidate scan() {
        Candidate best = null;
        double r = range.getValue();
        double lo = -Math.floor(r);
        double hi = Math.ceil(r);

        BlockPos.Mutable mutable = new BlockPos.Mutable();
        Vec3d eyes = mc.player.getEyePos();

        for (double dx = lo; dx < hi; dx++) {
            for (double dy = lo; dy < hi; dy++) {
                for (double dz = lo; dz < hi; dz++) {
                    mutable.set(mc.player.getX() + dx, mc.player.getEyeY() + dy, mc.player.getZ() + dz);

                    boolean isAnchor = isAnchor(mutable);
                    if (!isAnchor && !canPlaceAt(mutable))
                        continue;

                    if (mutable.getY() < mc.world.getBottomY() || mutable.getY() >= mc.world.getTopY())
                        continue;

                    boolean visible = isVisible(mutable);
                    double dist = eyes.distanceTo(mutable.toCenterPos());
                    if (!((visible || dist <= wallRange.getValue()) && dist <= range.getValue()))
                        continue;

                    Direction dir = findSupportDirection(mutable, wallRange.getValue() <= 0f);
                    PlayerDamage info = findBestTarget(mutable);

                    // existing anchors always use DOWN; air place falls back to DOWN when nothing to click on
                    if (isAnchor || (dir == null && airPlace.getValue()))
                        dir = Direction.DOWN;
                    if (dir == null && !mc.world.getFluidState(mutable).isEmpty() && liquidPlace.getValue())
                        dir = Direction.DOWN;

                    if (dir == null || info == null)
                        continue;

                    if (shouldSkip(mutable, info))
                        continue;

                    Candidate cand = new Candidate(mutable.toImmutable(), dir, info.player(), info.damage());
                    if (isAnchor)
                        return cand;

                    if (best == null || cand.damage() >= best.damage())
                        best = cand;
                }
            }
        }
        return best;
    }

    private record PlayerDamage(PlayerEntity player, float damage) {
    }

    private PlayerDamage findBestTarget(BlockPos pos) {
        PlayerDamage best = null;
        boolean anchor = isAnchor(pos);
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (isInvalidTarget(player))
                continue;
            float dmg = anchorDamage(pos.toCenterPos(), player, anchor ? pos : null);
            if (dmg < minDamage.getValue() || dmg < 0.5f)
                continue;
            if (best == null || dmg > best.damage())
                best = new PlayerDamage(player, dmg);
        }
        return best;
    }

    private boolean isInvalidTarget(PlayerEntity player) {
        return player == null
                || player == mc.player
                || !player.isAlive()
                || Managers.FRIEND.isFriend(player)
                || player.isSpectator()
                || player.getAbilities().creativeMode
                || mc.player.distanceTo(player) > targetRange.getValue();
    }

    /** true -> this position must be skipped (not safe or not worth it). */
    private boolean shouldSkip(BlockPos pos, PlayerDamage info) {
        boolean anchor = isAnchor(pos);
        float selfDamage = anchorDamage(pos.toCenterPos(), mc.player, anchor ? pos : null);

        float targetHp = info.player().getHealth() + info.player().getAbsorptionAmount();
        float selfHp = mc.player.getHealth() + mc.player.getAbsorptionAmount();

        boolean kills = info.damage() >= targetHp;
        boolean unsafe = !((kills || selfDamage < maxSelfDamage.getValue())
                && (selfDamage < selfHp || !antiSuicide.getValue()));

        if (anchor && !unsafe)
            return false;

        if (info.damage() < minDamage.getValue() || info.damage() < 0.5f)
            return true;

        return unsafe;
    }

    /* ------------------------------------------------------------------ */
    /*  pause                                                             */
    /* ------------------------------------------------------------------ */

    private boolean shouldPause() {
        if (mc.player.isSleeping()) return true;
        if (mc.player.isUsingItem() && eating.getValue()) return true;
        if (mc.interactionManager.isBreakingBlock() && mining.getValue()) return true;

        // let AutoCrystal go first when it has a better target
        if (thunder.hack.core.manager.client.ModuleManager.autoCrystal.isEnabled()
                && bestPlayer != null
                && thunder.hack.core.manager.client.ModuleManager.autoCrystal.getBestDamage() > bestDamage)
            return true;

        return mc.player.getHealth() + mc.player.getAbsorptionAmount() < health.getValue();
    }

    /* ------------------------------------------------------------------ */
    /*  actions                                                           */
    /* ------------------------------------------------------------------ */

    private void chargeAnchor(BlockPos pos, Direction dir, int prevSlot) {
        Vec3d vec = Vec3d.ofCenter(pos).offset(dir, 0.5);
        Hand glowHand = getHandHolding(Items.GLOWSTONE);

        if (rotate.getValue())
            rotateTo(vec);

        if (glowHand != null) {
            clickBlock(pos, dir, vec, Hand.MAIN_HAND, Hand.MAIN_HAND);
        } else {
            int slot = findHotbar(Items.GLOWSTONE);
            if (slot != -1) {
                InventoryUtility.switchTo(slot);
                clickBlock(pos, dir, vec, Hand.MAIN_HAND, Hand.MAIN_HAND);
                InventoryUtility.switchTo(prevSlot);
            }
        }
        delayTimer.reset();
    }

    private void placeAnchor(BlockPos pos, Direction dir, Hand hand) {
        if (pos == null || dir == null || isAnchor(pos)) return;

        int anchorSlot = findHotbar(Items.RESPAWN_ANCHOR);
        if (anchorSlot == -1 && hand == Hand.MAIN_HAND && !mc.player.getMainHandStack().isOf(Items.RESPAWN_ANCHOR))
            return;

        int prevSlot = mc.player.getInventory().selectedSlot;
        boolean airTrick = findSupportDirection(pos, false) == null && airPlace.getValue();

        BlockPos support = pos.offset(dir);
        Vec3d vec = getPlaceVec(support, dir);

        if (rotate.getValue())
            rotateTo(vec);

        boolean needSwitch = !mc.player.getMainHandStack().isOf(Items.RESPAWN_ANCHOR) && anchorSlot != -1;
        if (needSwitch)
            InventoryUtility.switchTo(anchorSlot);

        if (airTrick) {
            hand = Hand.OFF_HAND;
            sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
        }

        boolean placed = placeBlock(pos, vec, dir, hand);

        if (airTrick)
            sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));

        if (needSwitch)
            InventoryUtility.switchTo(prevSlot);

        if (placed && render.getValue())
            renderPositions.put(pos, System.currentTimeMillis());
    }

    /** Places the block currently in {@code hand} at {@code pos}, clicking the neighbour on side {@code dir}. */
    private boolean placeBlock(BlockPos pos, Vec3d vec, Direction dir, Hand hand) {
        if (!canPlaceAt(pos)) return false;

        BlockPos clickPos = pos.offset(dir);
        boolean sneak = needsSneak(clickPos) && !mc.player.isSneaking();

        if (sneak)
            sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY));

        BlockHitResult bhr = new BlockHitResult(vec, dir.getOpposite(), clickPos, false);
        sendSequencedPacket(id -> new PlayerInteractBlockC2SPacket(hand, bhr, id));
        mc.player.swingHand(hand);

        if (sneak)
            sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY));
        return true;
    }

    private void clickBlock(BlockPos pos, Direction dir, Vec3d vec, Hand hand, Hand swingHand) {
        BlockHitResult bhr = new BlockHitResult(vec, dir, pos, false);
        boolean sneaking = mc.player.isSneaking();

        if (sneaking)
            sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY));

        sendSequencedPacket(id -> new PlayerInteractBlockC2SPacket(hand, bhr, id));
        mc.player.swingHand(swingHand);

        if (sneaking)
            sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY));
    }

    private void rotateTo(Vec3d vec) {
        float[] angle = InteractionUtility.calculateAngle(vec);
        sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(angle[0], angle[1], mc.player.isOnGround()));
    }

    /* ------------------------------------------------------------------ */
    /*  hands / inventory                                                 */
    /* ------------------------------------------------------------------ */

    private Hand getAnchorHand() {
        return mc.player.getOffHandStack().isOf(Items.RESPAWN_ANCHOR) ? Hand.OFF_HAND : Hand.MAIN_HAND;
    }

    private Hand getHandHolding(Item item) {
        if (mc.player.getOffHandStack().isOf(item)) return Hand.OFF_HAND;
        if (mc.player.getMainHandStack().isOf(item)) return Hand.MAIN_HAND;
        return null;
    }

    private int findHotbar(Item item) {
        for (int i = 0; i < 9; i++)
            if (mc.player.getInventory().getStack(i).isOf(item))
                return i;
        return -1;
    }

    /* ------------------------------------------------------------------ */
    /*  geometry / placement helpers                                      */
    /* ------------------------------------------------------------------ */

    private boolean isAnchor(BlockPos pos) {
        return mc.world.getBlockState(pos).isOf(Blocks.RESPAWN_ANCHOR);
    }

    /** Can an anchor be placed here (world limits and entities in the way). */
    private boolean canPlaceAt(BlockPos pos) {
        if (!mc.world.getWorldBorder().contains(pos) || !mc.world.isInBuildLimit(pos))
            return false;
        if (pos.getY() < mc.world.getBottomY())
            return false;

        BlockState state = mc.world.getBlockState(pos);
        if (!state.isAir() && !state.isReplaceable())
            return false;

        Box box = new Box(pos);
        return mc.world.getEntitiesByClass(Entity.class, box, en -> !(en instanceof ArrowEntity)).isEmpty();
    }

    /** First side of {@code pos} that has a solid block we can click on. */
    private Direction findSupportDirection(BlockPos pos, boolean requireVisible) {
        Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.UP};
        for (Direction dir : order) {
            BlockPos neighbour = pos.offset(dir);
            if (neighbour.getY() >= mc.world.getTopY())
                continue;

            if (requireVisible && !isFaceVisible(neighbour, dir.getOpposite()))
                continue;

            BlockState st = mc.world.getBlockState(neighbour);
            if (!st.isAir() && !st.isReplaceable() && !st.getOutlineShape(mc.world, neighbour).isEmpty())
                return dir;
        }
        return null;
    }

    /** Face of the anchor block we click when charging / exploding. Mirrors Mio's side filtering. */
    private Direction getClickDirection(BlockPos pos) {
        List<Direction> dirs = new ArrayList<>(List.of(Direction.values()));

        for (Direction dir : Direction.values()) {
            BlockPos n = pos.offset(dir);
            BlockState st = mc.world.getBlockState(n);
            if (mc.world.isInBuildLimit(n) && st.isOpaqueFullCube(mc.world, n))
                dirs.remove(dir);
        }
        if (pos.getY() + 1 >= mc.world.getTopY())
            dirs.remove(Direction.UP);

        Vec3d eyes = mc.player.getEyePos();
        Vec3d center = pos.toCenterPos();
        double dx = eyes.x - center.x;
        double dz = eyes.z - center.z;

        if (dx > 0.5) dirs.remove(Direction.WEST);
        else if (dx < -0.5) dirs.remove(Direction.EAST);
        else {
            dirs.remove(Direction.WEST);
            dirs.remove(Direction.EAST);
        }

        if (dz > 0.5) dirs.remove(Direction.NORTH);
        else if (dz < -0.5) dirs.remove(Direction.SOUTH);
        else {
            dirs.remove(Direction.NORTH);
            dirs.remove(Direction.SOUTH);
        }

        if (eyes.y < pos.getY()) dirs.remove(Direction.UP);
        if (eyes.y > pos.getY()) dirs.remove(Direction.DOWN);

        return dirs.isEmpty() ? Direction.DOWN : dirs.get(0);
    }

    private boolean needsSneak(BlockPos pos) {
        BlockState st = mc.world.getBlockState(pos);
        Block b = st.getBlock();
        return b == Blocks.RESPAWN_ANCHOR || st.hasBlockEntity()
                || b instanceof BedBlock || b instanceof DoorBlock || b instanceof TrapdoorBlock
                || b instanceof FenceGateBlock || b instanceof AnvilBlock || b instanceof CraftingTableBlock
                || b instanceof ButtonBlock || b instanceof LeverBlock;
    }

    private Vec3d getPlaceVec(BlockPos support, Direction dir) {
        Direction face = dir.getOpposite();
        Vec3d faceCenter = Vec3d.ofCenter(support).offset(face, 0.5);
        Vec3d eyes = mc.player.getEyePos();

        // try the face centre and a few offsets; take the first one we can see (or reach)
        double[] offs = {0.0, 0.25, -0.25};
        for (double a : offs) {
            for (double b : offs) {
                Vec3d p = switch (face.getAxis()) {
                    case X -> faceCenter.add(0, a, b);
                    case Y -> faceCenter.add(a, 0, b);
                    case Z -> faceCenter.add(a, b, 0);
                };
                if (canSee(eyes, p, support) || eyes.distanceTo(p) <= wallRange.getValue())
                    return p;
            }
        }
        return faceCenter;
    }

    private boolean canSee(Vec3d from, Vec3d to, BlockPos expected) {
        BlockHitResult r = mc.world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
        return r == null || r.getType() == HitResult.Type.MISS || r.getBlockPos().equals(expected);
    }

    private boolean isVisible(BlockPos pos) {
        Vec3d eyes = mc.player.getEyePos();
        for (Direction dir : Direction.values()) {
            if (isFaceVisible(pos, dir, eyes))
                return true;
        }
        return false;
    }

    private boolean isFaceVisible(BlockPos pos, Direction face) {
        return isFaceVisible(pos, face, mc.player.getEyePos());
    }

    private boolean isFaceVisible(BlockPos pos, Direction face, Vec3d eyes) {
        Vec3d p = Vec3d.ofCenter(pos).offset(face, 0.5);
        BlockHitResult r = mc.world.raycast(new RaycastContext(eyes, p, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
        return r == null || r.getType() == HitResult.Type.MISS || r.getBlockPos().equals(pos);
    }

    /* ------------------------------------------------------------------ */
    /*  damage                                                            */
    /* ------------------------------------------------------------------ */

    /** Respawn anchor explosion damage (power 5). {@code ignored} is the anchor block that is removed before the blast. */
    private float anchorDamage(Vec3d source, PlayerEntity entity, BlockPos ignored) {
        if (entity == null || mc.world.getDifficulty() == Difficulty.PEACEFUL)
            return 0f;
        if (entity == mc.player && mc.player.isCreative())
            return 0f;

        BlockState prev = null;
        if (ignored != null) {
            prev = mc.world.getBlockState(ignored);
            mc.world.setBlockState(ignored, Blocks.AIR.getDefaultState());
        }

        try {
            Box box = entity.getBoundingBox();
            Vec3d feet = new Vec3d(MathHelper.lerp(0.5, box.minX, box.maxX), box.minY, MathHelper.lerp(0.5, box.minZ, box.maxZ));
            double diameter = POWER * 2.0;
            double dist = feet.distanceTo(source) / diameter;
            if (dist > 1.0)
                return 0f;

            double exposure = ExplosionUtility.getExposure(source, box, true);
            double impact = (1.0 - dist) * exposure;
            float dmg = (float) ((impact * impact + impact) / 2.0 * 7.0 * diameter + 1.0);

            if (mc.world.getDifficulty() == Difficulty.EASY) dmg = Math.min(dmg / 2f + 1f, dmg);
            else if (mc.world.getDifficulty() == Difficulty.HARD) dmg = dmg * 3f / 2f;

            dmg = DamageUtil.getDamageLeft(entity, dmg, mc.world.getDamageSources().explosion((net.minecraft.world.explosion.Explosion) null),
                    entity.getArmor(), (float) entity.getAttributeInstance(EntityAttributes.GENERIC_ARMOR_TOUGHNESS).getValue());

            if (entity.hasStatusEffect(StatusEffects.RESISTANCE)) {
                int amp = entity.getStatusEffect(StatusEffects.RESISTANCE).getAmplifier();
                dmg = dmg * (25f - (amp + 1) * 5f) / 25f;
            }

            if (dmg <= 0f) return 0f;

            int prot = ExplosionUtility.getProtectionAmount(entity.getArmorItems());
            if (prot > 0)
                dmg = DamageUtil.getInflictedDamage(dmg, prot);

            return Math.max(dmg, 0f);
        } finally {
            if (ignored != null && prev != null)
                mc.world.setBlockState(ignored, prev);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  render                                                            */
    /* ------------------------------------------------------------------ */

    @Override
    public void onRender3D(MatrixStack stack) {
        if (!render.getValue() || renderPositions.isEmpty()) return;

        long now = System.currentTimeMillis();
        long life = Math.max(1L, (long) (fadeTime.getValue() * 1000f));

        renderPositions.entrySet().removeIf(en -> now - en.getValue() > life);

        for (Map.Entry<BlockPos, Long> en : renderPositions.entrySet()) {
            float k = fade.getValue() ? MathHelper.clamp(1f - (now - en.getValue()) / (float) life, 0f, 1f) : 1f;

            Color f = fill.getValue().getColorObject();
            Color o = outline.getValue().getColorObject();
            Box box = new Box(en.getKey());

            Render3DEngine.FILLED_QUEUE.add(new Render3DEngine.FillAction(box, Render2DEngine.injectAlpha(f, (int) (f.getAlpha() * k))));
            Render3DEngine.OUTLINE_QUEUE.add(new Render3DEngine.OutlineAction(box, Render2DEngine.injectAlpha(o, (int) (o.getAlpha() * k)), 2));
        }
    }
}
