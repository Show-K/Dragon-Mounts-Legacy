package wolfshotz.dml.entity.dragons;

import com.google.common.collect.Lists;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SoundType;
import net.minecraft.entity.*;
import net.minecraft.entity.ai.controller.BodyController;
import net.minecraft.entity.ai.goal.SitGoal;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SaddleItem;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.network.play.server.SAnimateHandPacket;
import net.minecraft.pathfinding.FlyingPathNavigator;
import net.minecraft.pathfinding.GroundPathNavigator;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.DamageSource;
import net.minecraft.util.Hand;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.gen.Heightmap;
import net.minecraft.world.server.ServerWorld;
import wolfshotz.dml.DragonMountsLegacy;
import wolfshotz.dml.entity.dragons.ai.DragonBodyController;
import wolfshotz.dml.entity.dragons.ai.DragonBrainController;
import wolfshotz.dml.entity.dragons.ai.LifeStageController;

import javax.annotation.Nullable;
import java.util.List;
import java.util.logging.Logger;

import static net.minecraft.entity.SharedMonsterAttributes.*;

/**
 * Here be dragons.
 * <p>
 * Recreated: 10:50PM, 4/3/2020
 * Let the legacy live on
 *
 * @author Nico Bergemann <barracuda415 at yahoo.de>
 * @author WolfShotz
 */
public class TameableDragonEntity extends TameableEntity
{
    // base attributes
    public static final double BASE_SPEED_GROUND = 0.3;
    public static final double BASE_SPEED_FLYING = 0.4;
    public static final double BASE_DAMAGE = 8;
    public static final double BASE_HEALTH = 60;
    public static final float BASE_WIDTH = 2.75f; // adult sizes
    public static final float BASE_HEIGHT = 2.75f;
    public static final double BASE_FOLLOW_RANGE = 16;
    public static final double BASE_FOLLOW_RANGE_FLYING = BASE_FOLLOW_RANGE * 2;
    public static final int HOME_RADIUS = 64;
    public static final double ALTITUDE_FLYING_THRESHOLD = 2;
    public static final int REPRO_LIMIT = 2;
    private static final Logger L = Logger.getLogger(DragonMountsLegacy.MOD_ID);
    // data value IDs
    private static final DataParameter<Boolean> DATA_FLYING = EntityDataManager.createKey(TameableDragonEntity.class, DataSerializers.BOOLEAN);
    private static final DataParameter<Boolean> DATA_SADDLED = EntityDataManager.createKey(TameableDragonEntity.class, DataSerializers.BOOLEAN);
    private static final DataParameter<Integer> DATA_TICKS_ALIVE = EntityDataManager.createKey(TameableDragonEntity.class, DataSerializers.VARINT);

    // data NBT IDs
    private static final String NBT_SADDLED = "Saddle";
    private static final String NBT_TICKS_ALIVE = "TicksAlive";
    private static final String NBT_REPRO_COUNT = "ReproCount";
    public final List<DamageSource> damageImmunities = Lists.newArrayList();
    // server/client delegates
    public LifeStageController lifeStageController;
    public DragonBrainController dragonBrainController;
    public int reproCount;

    public TameableDragonEntity(EntityType<? extends TameableDragonEntity> type, World world)
    {
        super(type, world);

        // enables walking over blocks
        stepHeight = 1;
        ignoreFrustumCheck = true;
    }

    @Override
    protected BodyController createBodyController()
    {
        return new DragonBodyController(this);
    }

    @Override
    protected void registerAttributes()
    {
        super.registerAttributes();

        getAttribute(MOVEMENT_SPEED).setBaseValue(BASE_SPEED_GROUND);
        getAttribute(MAX_HEALTH).setBaseValue(BASE_HEALTH);
        getAttribute(FOLLOW_RANGE).setBaseValue(BASE_FOLLOW_RANGE);
        getAttributes().registerAttribute(ATTACK_DAMAGE).setBaseValue(BASE_DAMAGE);
        getAttributes().registerAttribute(FLYING_SPEED).setBaseValue(BASE_SPEED_FLYING);
    }

    @Override
    protected void registerGoals()
    {
        sitGoal = new SitGoal(this);
        getDragonBrainController().updateGoals();
    }

    @Override
    protected void registerData()
    {
        super.registerData();

        dataManager.register(DATA_FLYING, false);
        dataManager.register(DATA_SADDLED, false);
        dataManager.register(DATA_TICKS_ALIVE, LifeStageController.EnumLifeStage.ADULT.startTicks()); // default to adult stage
    }

    @Override
    public void writeAdditional(CompoundNBT compound)
    {
        super.writeAdditional(compound);
        compound.putBoolean(NBT_SADDLED, isSaddled());
        compound.putInt(NBT_TICKS_ALIVE, getTicksAlive());
        compound.putInt(NBT_REPRO_COUNT, reproCount);
    }

    @Override
    public void readAdditional(CompoundNBT compound)
    {
        super.readAdditional(compound);
        setSaddled(compound.getBoolean(NBT_SADDLED));
        setTicksAlive(compound.getInt(NBT_TICKS_ALIVE));
        this.reproCount = compound.getInt(NBT_REPRO_COUNT);
    }

    /**
     * Returns true if the dragon is saddled.
     */
    public boolean isSaddled()
    {
        return dataManager.get(DATA_SADDLED);
    }

    /**
     * Set or remove the saddle of the dragon.
     */
    public void setSaddled(boolean saddled)
    {
        dataManager.set(DATA_SADDLED, saddled);
    }

    public int getTicksAlive()
    {
        return dataManager.get(DATA_TICKS_ALIVE);
    }

    public void setTicksAlive(int ticksAlive)
    {
        dataManager.set(DATA_TICKS_ALIVE, ticksAlive);
        lifeStageController.setTicksAlive(ticksAlive);
    }

    public boolean canFly()
    {
        // hatchlings can't fly
        return !isHatchling();
    }

    /**
     * Returns true if the entity is flying.
     */
    public boolean isFlying()
    {
        return dataManager.get(DATA_FLYING);
    }

    /**
     * Set the flying flag of the entity.
     */
    public void setFlying(boolean flying)
    {
        dataManager.set(DATA_FLYING, flying);
    }

    /**
     * Returns the distance to the ground while the entity is flying.
     */
    public double getAltitude()
    {
        BlockPos groundPos = world.getHeight(Heightmap.Type.WORLD_SURFACE, getPosition());
        return getPosY() - groundPos.getY();
    }

    /**
     * Causes this entity to lift off if it can fly.
     */
    public void liftOff()
    {
        if (canFly()) jump();
    }

    @Override
    protected float getJumpUpwardsMotion()
    {
        // stronger jumps for easier lift-offs
        return canFly() ? 1 : super.getJumpUpwardsMotion();
    }

    @Override
    public boolean onLivingFall(float distance, float damageMultiplier)
    {
        if (canFly()) return false;
        return super.onLivingFall(distance, damageMultiplier);
    }

    @Override
    public void livingTick()
    {
        lifeStageController.tick();

        if (isServer())
        {
            // set home position near owner when tamed
//            if (isTamed())
//            {
//                Entity owner = getOwner();
//                if (owner != null)
//                {
//                    setHomePosAndDistance(owner.getPosition(), HOME_RADIUS);
//                }
//            }

            // update flying state based on the distance to the ground
            boolean flying = canFly() && getAltitude() > ALTITUDE_FLYING_THRESHOLD;
            if (flying != isFlying())
            {
                // notify client
                setFlying(flying);

                // clear tasks (needs to be done before switching the navigator!)
                getDragonBrainController().clearGoals();

                // update AI follow range (needs to be updated before creating
                // new PathNavigate!)
                getAttribute(FOLLOW_RANGE).setBaseValue(flying ? BASE_FOLLOW_RANGE_FLYING : BASE_FOLLOW_RANGE);

                // update pathfinding method
                if (flying) navigator = new FlyingPathNavigator(this, world);
                else navigator = new GroundPathNavigator(this, world);

                // tasks need to be updated after switching modes
                getDragonBrainController().updateGoals();
            }
        }

        super.livingTick();
    }


//    yeah lol no this caused a shit ton of lag. lets try and handle this on both sides BY using this.
//
//    @Override
//    public void moveEntityWithHeading(float strafe, float forward)
//    {
//        // disable method while flying, the movement is done entirely by
//        // moveEntity() and this one just makes the dragon to fall slowly when
//        // hovering
//        if (!isFlying())
//        {
//            super.moveEntityWithHeading(strafe, forward);
//        }
//    }
//

    @Override
    public void travel(Vec3d vec3d)
    {
        // todo: try to solve shitty server-sided movement by using travel.
        super.travel(vec3d);
    }

    /**
     * Handles entity death timer, experience orb and particle creation
     */
    @Override
    protected void onDeathUpdate()
    {
        // unmount any riding entities
        removePassengers();

        // freeze at place
        setMotion(Vec3d.ZERO);
        rotationYaw = prevRotationYaw;
        rotationYawHead = prevRotationYawHead;


        if (deathTime >= getMaxDeathTime()) remove(); // actually delete entity after the time is up

        deathTime++;
    }

    /**
     * Returns the sound this mob makes while it's alive.
     */
    @Override
    protected SoundEvent getAmbientSound()
    {
        if (getRNG().nextInt(3) == 0) return SoundEvents.ENTITY_ENDER_DRAGON_GROWL;
        return null;
//                DragonMountsSoundEvents.ENTITY_DRAGON_MOUNT_BREATHE; TODO: custom sounds

    }

    @Nullable
    @Override
    protected SoundEvent getHurtSound(DamageSource damageSourceIn)
    {
        return SoundEvents.ENTITY_ENDER_DRAGON_HURT;
    }

    public SoundEvent getStepSound()
    {
        return null;
//        DragonMountsSoundEvents.ENTITY_DRAGON_MOUNT_STEP; TODO: custom sounds

    }

    /**
     * Returns the sound this mob makes on death.
     */
    @Override
    protected SoundEvent getDeathSound()
    {
        return null;
//                DragonMountsSoundEvents.ENTITY_DRAGON_MOUNT_DEATH; TODO: custom sounds
    }

    @Override
    public SoundEvent getEatSound(ItemStack itemStackIn)
    {
        return SoundEvents.ENTITY_GENERIC_EAT;
    }

    public SoundEvent getAttackSound()
    {
        return SoundEvents.ENTITY_GENERIC_EAT;
    }

    public SoundEvent getWingsSound()
    {
        return SoundEvents.ENTITY_ENDER_DRAGON_FLAP;
    }

    /**
     * Plays step sound at given x, y, z for the entity
     */
    @Override
    protected void playStepSound(BlockPos entityPos, BlockState state)
    {
        if (isInWater()) return;

        // override sound type if the top block is snowy
        SoundType soundType = state.getSoundType();
        if (world.getBlockState(entityPos.up()).getBlock() == Blocks.SNOW)
            soundType = Blocks.SNOW.getSoundType(state, world, entityPos, this);

        // play stomping for bigger dragons
        SoundEvent stepSound = getStepSound();
        if (isHatchling()) stepSound = soundType.getStepSound();

        playSound(stepSound, soundType.getVolume(), soundType.getPitch());
    }

    /**
     * Get number of ticks, at least during which the living entity will be silent.
     */
    @Override
    public int getTalkInterval()
    {
        return 240;
    }

//    todo: handle this in subclasses!
//    /**
//     * Get this Entity's EnumCreatureAttribute
//     */
//    @Override
//    public EnumCreatureAttribute getCreatureAttribute()
//    {
//        return getBreed().getCreatureAttribute();
//    }

    @Override
    protected float getSoundVolume()
    {
        return getScale();
    }

    @Override
    protected float getSoundPitch()
    {
        return getScale() - 2f;
    }

    @Override
    public void playSound(SoundEvent soundIn, float volume, float pitch)
    {
        playSound(soundIn, volume, pitch, false);
    }

    public void playSound(SoundEvent sound, float volume, float pitch, boolean local)
    {
        if (isSilent()) return;

        volume *= getSoundVolume();
        pitch *= getSoundPitch();

        if (local) world.playSound(getPosX(), getPosY(), getPosZ(), sound, getSoundCategory(), volume, pitch, false);
        else world.playSound(null, getPosX(), getPosY(), getPosZ(), sound, getSoundCategory(), volume, pitch);
    }

    /**
     * Called when a player interacts with a mob. e.g. gets milk from a cow, gets into the saddle on a pig.
     */
    @Override
    public boolean processInteract(PlayerEntity player, Hand hand)
    {
        ItemStack stack = player.getHeldItem(hand);

        if (isServer())
        {
            // heal
            if (getHealthRelative() < 1 && isFoodItem(stack))
            {
                stack.shrink(1);
                heal(stack.getItem().getFood().getHealing());
                playSound(getEatSound(stack), 0.7f, 1);
                return true;
            }

            // tame
            if (isBreedingItem(stack) && !isTamed())
            {
                stack.shrink(1);
                tamedFor(player, getRNG().nextInt(5) == 0);
                return true;
            }

            // saddle up!
            if (isTamedFor(player) && !isSaddled() && stack.getItem() instanceof SaddleItem)
            {
                stack.shrink(1);
                setSaddled(true);
                return true;
            }

            // sit!
            if (isTamedFor(player) && player.isShiftKeyDown())
            {
                sitGoal.setSitting(!isSitting());
                navigator.clearPath();
                return true;
            }

            // ride on
            if (isTamed() && isSaddled())
            {
                setRidingPlayer(player);
                return true;
            }
        }

        return super.processInteract(player, hand);
    }

    public boolean isFoodItem(ItemStack stack)
    {
        return stack.getItem().isFood() && stack.getItem().getFood().isMeat();
    }

    @Override
    public boolean isBreedingItem(ItemStack stack)
    {
        return ItemTags.FISHES.contains(stack.getItem());
    }

    public void tamedFor(PlayerEntity player, boolean successful)
    {
        if (successful)
        {
            setTamed(true);
            navigator.clearPath();
            setAttackTarget(null);
            setOwnerId(player.getUniqueID());
            playTameEffect(true);
            world.setEntityState(this, (byte) 7);
        }
        else
        {
            playTameEffect(false);
            world.setEntityState(this, (byte) 6);
        }
    }

    public boolean isTamedFor(PlayerEntity player)
    {
        return isTamed() && isOwner(player);
    }

    /**
     * Returns the height of the eyes. Used for looking at other entities.
     */
    @Override
    protected float getStandingEyeHeight(Pose poseIn, EntitySize sizeIn)
    {
        float eyeHeight = super.getStandingEyeHeight(poseIn, sizeIn);

        if (isSitting()) eyeHeight *= 0.8f;

        return eyeHeight;
    }

    /**
     * Returns the Y offset from the entity's position for any entity riding this one.
     */
    @Override
    public double getMountedYOffset()
    {
        return (isSitting() ? 1.7f : 2.2f) * getScale();
    }

    /**
     * Returns render size modifier
     */
    @Override
    public float getRenderScale()
    {
        return getScale();
    }

    /**
     * Determines if an entity can be despawned, used on idle far away entities
     */
    @Override
    public boolean canDespawn(double distanceToClosestPlayer)
    {
        return false;
    }

    /**
     * returns true if this entity is by a ladder, false otherwise
     */
    @Override
    public boolean isOnLadder()
    {
        // this better doesn't happen...
        return false;
    }

    @Override
    protected void dropSpecialItems(DamageSource source, int looting, boolean recentlyHitIn)
    {
        super.dropSpecialItems(source, looting, recentlyHitIn);

        if (isSaddled()) entityDropItem(Items.SADDLE);
    }

    public boolean attackEntityAsMob(Entity entityIn)
    {
        boolean attacked = entityIn.attackEntityFrom(DamageSource.causeMobDamage(this), (float) getAttribute(ATTACK_DAMAGE).getValue());

        if (attacked) applyEnchantments(this, entityIn);

        return attacked;
    }

    public void onWingsDown(float speed)
    {
        if (!isInWater())
        {
            // play wing sounds
            float pitch = (1 - speed);
            float volume = 0.3f + (1 - speed) * 0.2f;
            playSound(getWingsSound(), volume, pitch, true);
        }
    }

    @Override
    public void swingArm(Hand hand)
    {
        // play eating sound
        playSound(getAttackSound(), 1, 0.7f);

        // play attack animation
        if (isServer())
            ((ServerWorld) world).getChunkProvider().sendToAllTracking(this, new SAnimateHandPacket(this, 0));
    }

    /**
     * Called when the entity is attacked.
     */
    @Override
    public boolean attackEntityFrom(DamageSource src, float par2)
    {
        if (isInvulnerableTo(src)) return false;

        // don't just sit there!
        if (isServer()) sitGoal.setSitting(false);

        return super.attackEntityFrom(src, par2);
    }

    /**
     * Return whether this entity should be rendered as on fire.
     */
    @Override
    public boolean canRenderOnFire()
    {
        return super.canRenderOnFire() && !isImmuneToFire() && !isInvulnerableTo(DamageSource.IN_FIRE);
    }

    /**
     * Return whether we should be able to render tail scales.
     * Used for fire dragons
     */
    public boolean renderTailScales() { return false; }

    /**
     * Return whether we should be able to render tail scales.
     * Used for ghost dragons
     */
    public boolean renderThinLegs() { return false; }

    /**
     * Return whether we should be able to render tail scales.
     * Used for water dragons
     */
    public boolean renderTailHorns() { return false; }

    /**
     * Returns true if the mob is currently able to mate with the specified mob.
     */
    @Override
    public boolean canMateWith(AnimalEntity mate)
    {
        if (mate == this) return false; // No. Just... no.
        else if (!(mate instanceof TameableDragonEntity)) return false;
        else if (!canReproduce()) return false;

        TameableDragonEntity dragonMate = (TameableDragonEntity) mate;

        if (!dragonMate.isTamed()) return false;
        else if (!dragonMate.canReproduce()) return false;
        else return isInLove() && dragonMate.isInLove();
    }

    public boolean canReproduce() { return isTamed() && reproCount < REPRO_LIMIT; }

    /**
     * This function is used when two same-species animals in 'love mode' breed to generate the new baby animal.
     */
    @Override
    public AgeableEntity createChild(AgeableEntity mate)
    {
        return null; // TODO
//        if (!(mate instanceof AbstractTameableDragonEntity))
//            throw new IllegalArgumentException("The mate isn't a dragon");
//
//        AbstractTameableDragonEntity parent1 = this;
//        AbstractTameableDragonEntity parent2 = (AbstractTameableDragonEntity) mate;
//        AbstractTameableDragonEntity baby = null;
//
//        // mix the custom names in case both parents have one
//        if (parent1.hasCustomName() && parent2.hasCustomName()) {
//            String p1Name = parent1.getCustomName().getString();
//            String p2Name = parent2.getCustomName().getString();
//            String babyName;
//
//            if (p1Name.contains(" ") || p2Name.contains(" ")) {
//                // combine two words with space
//                // "Tempor Invidunt Dolore" + "Magna"
//                // = "Tempor Magna" or "Magna Tempor"
//                String[] p1Names = p1Name.split(" ");
//                String[] p2Names = p2Name.split(" ");
//
//                p1Name = fixChildName(p1Names[rand.nextInt(p1Names.length)]);
//                p2Name = fixChildName(p2Names[rand.nextInt(p2Names.length)]);
//
//                babyName = rand.nextBoolean() ? p1Name + " " + p2Name : p2Name + " " + p1Name;
//            } else {
//                // scramble two words
//                // "Eirmod" + "Voluptua"
//                // = "Eirvolu" or "Volueir" or "Modptua" or "Ptuamod" or ...
//                if (rand.nextBoolean()) {
//                    p1Name = p1Name.substring(0, (p1Name.length() - 1) / 2);
//                } else {
//                    p1Name = p1Name.substring((p1Name.length() - 1) / 2);
//                }
//
//                if (rand.nextBoolean()) {
//                    p2Name = p2Name.substring(0, (p2Name.length() - 1) / 2);
//                } else {
//                    p2Name = p2Name.substring((p2Name.length() - 1) / 2);
//                }
//
//                p2Name = fixChildName(p2Name);
//
//                babyName = rand.nextBoolean() ? p1Name + p2Name : p2Name + p1Name;
//            }
//
//            baby.setCustomNameTag(babyName);
//        }
//
//        // inherit the baby's breed from its parents
//        baby.getBreedHelper().inheritBreed(parent1, parent2);
//
//        // increase reproduction counter
//        parent1.getReproductionHelper().addReproduced();
//        parent2.getReproductionHelper().addReproduced();
//
//        return baby;
    }

    public LifeStageController getLifeStageController()
    {
        if (lifeStageController == null) lifeStageController = new LifeStageController(this);
        return lifeStageController;
    }

    public DragonBrainController getDragonBrainController()
    {
        if (dragonBrainController == null) dragonBrainController = new DragonBrainController(this);
        return dragonBrainController;
    }

    /**
     * For vehicles, the first passenger is generally considered the controller and "drives" the vehicle. For example,
     * Pigs, Horses, and Boats are generally "steered" by the controlling passenger.
     */
    @Override
    public Entity getControllingPassenger()
    {
        List<Entity> list = getPassengers();
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public boolean canPassengerSteer()
    {
        // must always return false or the vanilla movement code interferes
        // with DragonMoveHelper
        return false;
    }

    public PlayerEntity getRidingPlayer()
    {
        Entity entity = getControllingPassenger();
        if (entity instanceof PlayerEntity) return (PlayerEntity) entity;
        else return null;
    }

    public void setRidingPlayer(PlayerEntity player)
    {
        player.rotationYaw = rotationYaw;
        player.rotationPitch = rotationPitch;
        player.startRiding(this);
    }

//    @Override
//    public void updateRiderPosition() {
//        if (riddenByEntity != null) {
//            double px = posX;
//            double py = posY + getMountedYOffset() + riddenByEntity.getYOffset();
//            double pz = posZ;
//
//            // dragon position is the middle of the model and the saddle is on
//            // the shoulders, so move player forwards on Z axis relative to the
//            // dragon's rotation to fix that
//            Vec3 pos = new Vec3(0, 0, 0.8 * getScale());
//            pos = pos.rotateYaw((float) Math.toRadians(-renderYawOffset)); // oops
//            px += pos.xCoord;
//            py += pos.yCoord;
//            pz += pos.zCoord;
//
//            riddenByEntity.setPosition(px, py, pz);
//
//            // fix rider rotation
//            if (riddenByEntity instanceof EntityLiving) {
//                EntityLiving rider = ((EntityLiving) riddenByEntity);
//                rider.prevRotationPitch = rider.rotationPitch;
//                rider.prevRotationYaw = rider.rotationYaw;
//                rider.renderYawOffset = renderYawOffset;
//            }
//        }
//    }

    public boolean isInvulnerableTo(DamageSource src)
    {
        Entity srcEnt = src.getTrueSource();
        if (srcEnt != null)
        {
            // ignore own damage
            if (srcEnt == this) return true;

            // ignore damage from riders
            if (isPassenger(srcEnt)) return true;
        }

        return damageImmunities.contains(src);
    }

    /**
     * Returns the entity's health relative to the maximum health.
     *
     * @return health normalized between 0 and 1
     */
    public double getHealthRelative() { return getHealth() / (double) getMaxHealth(); }

    public int getDeathTime() { return deathTime; }

    public int getMaxDeathTime() { return 120; }

    public void setAttackDamage(double damage)
    {
        getAttribute(ATTACK_DAMAGE).setBaseValue(damage);
    }

    /**
     * Public wrapper for protected final setScale(), used by DragonLifeStageHelper.
     */
    @Override
    public void recalculateSize()
    {
        double posXTmp = getPosX();
        double posYTmp = getPosY();
        double posZTmp = getPosZ();
        boolean onGroundTmp = onGround;

        super.recalculateSize();

        // workaround for a vanilla bug; the position is apparently not set correcty
        // after changing the entity size, causing asynchronous server/client positioning
        setPosition(posXTmp, posYTmp, posZTmp);

        // otherwise, setScale stops the dragon from landing while it is growing
        onGround = onGroundTmp;
    }

    /**
     * The age value may be negative or positive or zero. If it's negative, it get's incremented on each tick, if it's
     * positive, it get's decremented each tick. Don't confuse this with EntityLiving.getAge. With a negative value the
     * Entity is considered a child.
     */
    @Override
    public int getGrowingAge()
    {
        // adapter for vanilla code to enable breeding interaction
        return isAdult() ? 0 : -1;
    }

    /**
     * The age value may be negative or positive or zero. If it's negative, it get's incremented on each tick, if it's
     * positive, it get's decremented each tick. With a negative value the Entity is considered a child.
     */
    @Override
    public void setGrowingAge(int age)
    {
        // managed by DragonLifeStageHelper, so this is a no-op
    }

    @Override
    public EntitySize getSize(Pose poseIn) { return new EntitySize(BASE_WIDTH * getScale(), BASE_HEIGHT * getScale(), false); }

    /**
     * Returns the size multiplier for the current age.
     *
     * @return scale
     */
    public float getScale()
    {
        return getLifeStageController().getScale();
    }

    public boolean isHatchling()
    {
        return getLifeStageController().isHatchling();
    }

    public boolean isJuvenile()
    {
        return getLifeStageController().isJuvenile();
    }

    public boolean isAdult()
    {
        return getLifeStageController().isAdult();
    }

    @Override
    public boolean isChild()
    {
        return !isAdult();
    }

    /**
     * Checks if this entity is running on a client.
     * <p>
     * Required since MCP's isClientWorld returns the exact opposite...
     *
     * @return true if the entity runs on a client or false if it runs on a server
     */
    public final boolean isClient()
    {
        return world.isRemote;
    }

    /**
     * Checks if this entity is running on a server.
     *
     * @return true if the entity runs on a server or false if it runs on a client
     */
    public final boolean isServer()
    {
        return !world.isRemote;
    }
}