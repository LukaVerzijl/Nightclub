package exposed.hydrogen.nightclub.light;

import com.google.gson.JsonArray;
import exposed.hydrogen.nightclub.Nightclub;
import exposed.hydrogen.nightclub.light.data.*;
import exposed.hydrogen.nightclub.light.event.LightChannel;
import exposed.hydrogen.nightclub.light.event.LightSpeedChannel;
import exposed.hydrogen.nightclub.util.Location;
import exposed.hydrogen.nightclub.wrapper.DebugMarkerWrapper;
import exposed.hydrogen.nightclub.wrapper.LaserWrapper;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.math3.geometry.euclidean.threed.Rotation;
import org.apache.commons.math3.geometry.euclidean.threed.RotationConvention;
import org.apache.commons.math3.geometry.euclidean.threed.Vector3D;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.util.List;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

@EqualsAndHashCode
public class Light implements LightI {
    private transient ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);
    private static final transient int DELAY = 100; // run every x ms
    // annotations lol
    @Getter private final UUID uniqueID;
    @Getter @Setter private String name;
    @Getter @Setter private Location location; // only changes when manually set
    @Getter @Setter private LightData data;
    @Getter private LightType type;
    @Getter private LightChannel channel;
    @Getter private LightSpeedChannel speedChannel;
    @Getter private transient DebugMarkerWrapper marker;

    private final transient List<LaserWrapper> lasers = new LinkedList<>();
    private transient double length = 0; // 0 to 100, percentage of maxLength.
    private transient double zoomTime = 0; // 0 to 1, current zoom time. does nothing if <=0
    private transient Location loc; // current location
    @Getter @Setter private transient double x = 0; // 0 to 100, usually percentage of 360
    @Getter @Setter private transient double x2 = 0; // 0 to 100, usually percentage of 360, secondary pattern
    private transient boolean isOn = false;
    private transient boolean isZoomed = false;
    private transient double multipliedSpeed; // speed, but when internally multiplied by events
    private transient double secondaryMultipliedSpeed;
    private transient int timeToFade; // internal fade off value
    private final transient Runnable run;
    private transient boolean isLoaded;

    private Light() {
        this(new Location(0, 0, 0, 0, 0), LightPattern.STILL, LightType.GUARDIAN_BEAM, LightChannel.CENTER_LIGHTS);
    }

    public Light(Location loc, LightPattern pattern, LightType type, LightChannel channel) {
        this(loc, "Unnamed-Light" + new Random().nextInt(), pattern, type, channel);
    }

    public Light(Location loc, String name, LightPattern pattern, LightType type, LightChannel channel) {
        this(loc, UUID.randomUUID(), name, pattern, type, channel);
    }

    public Light(Location loc, UUID uniqueID, String name, LightPattern pattern, LightType type, LightChannel channel) {
        this(uniqueID, name, loc, type, channel, LightSpeedChannel.DEFAULT, new LightData(
                new LightPatternData(pattern, 0, 0, 0, 0), new LightPatternData(LightPattern.STILL,
                0, 0, 0, 0), new ArrayList<>(), new RingMovementData(new Location(), 0, 0), 0, 0, 0,
                0, false));
    }

    @Builder
    public Light(UUID uuid, String name, Location location, LightType type, LightChannel channel, LightSpeedChannel speedChannel, LightData data) {
        this.uniqueID = uuid;
        this.name = name;
        this.location = location;
        this.type = type;
        this.channel = channel;
        this.speedChannel = speedChannel;
        this.data = data;

        run = () -> {
            try {
                if (isZoomed ? zoomTime < 1 : zoomTime > 0) {
                    double duration = getData().getRingMovementData().getDuration();
                    zoomTime = isZoomed ?
                            zoomTime + duration / 1000 / (DELAY / 10.0)
                            : zoomTime - duration / 1000 / (DELAY / 10.0);
                }
                if(zoomTime < 0 && !isZoomed) {
                    zoomTime = 0;
                }
                if(zoomTime > 1 && isZoomed) {
                    zoomTime = 1;
                }
                if (timeToFade > 0 && length > 0) {
                    timeToFade--;
                    length -= 100.0 / this.data.getTimeToFadeToBlack();
                }
                if (length < 0.1) {
                    off(new Color(0x000000));
                    timeToFade = 0;
                    length = 0.1;
                }
                if (length > 100) {
                    length = 100.0;
                }
                x = (x + multipliedSpeed) % 100;
                x2 = (x2 + secondaryMultipliedSpeed) % 100;

                for (int i = 0; i < lasers.size(); i++) {
                    LaserWrapper laser = lasers.get(i);
                    /*
                    Here we make a ray the size of (length) from the location of this Light, then we add a 2d plane to it (which is where our pattern is) with an
                    x value that is separated evenly for each laser. This pattern is then moved (as a whole) by the second pattern.
                    The pattern should also curve.
                    */
                    // x position evenly separated for each laser
                    double separated = x + (100.0 / lasers.size()) * i;
                    // a (invisible) "ray" the size of length, pointing towards the set pitch and yaw
                    Vector3D v = new Vector3D(Math.toRadians(this.location.getYaw()), Math.toRadians(this.location.getPitch())).normalize().scalarMultiply(getMaxLengthPercent());
                    // rotation for first and second patterns, determines "start position" when pattern is a circle
                    Rotation r = new Rotation(v, this.data.getPatternData().getRotation(), RotationConvention.FRAME_TRANSFORM);
                    Rotation r2 = new Rotation(v, this.data.getSecondPatternData().getRotation(), RotationConvention.FRAME_TRANSFORM);
                    // apply first pattern (separated evenly for each laser) to our ray
                    Vector3D v2 = this.data.getPatternData().getPattern().apply(v, separated, r, this.data.getPatternData().getPatternSizeMultiplier() * (length / 100));
                    // then apply second pattern to all lasers with the same x value
                    Vector3D v3 = this.data.getSecondPatternData().getPattern().apply(v, x2, r2, this.data.getSecondPatternData().getPatternSizeMultiplier() * (length / 100));
                    Vector3D v4 = getData().getRingMovementData().calculateMovement(zoomTime);
                    Vector3D v5 = v.add(v4).add(v3).add(v2);

                    Location result = this.location.clone().add(v5.getX(), v5.getZ(), v5.getY());
                    Location startResult = this.location.clone().add(v4.getX(), v4.getZ(), v4.getY());
                    loc = startResult;
                    if (this.data.isFlipStartAndEnd()) {
                        laser.setStart(result);
                        laser.setEnd(startResult);
                    } else {
                        laser.setEnd(result);
                        laser.setStart(startResult);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        };
    }

    public void load() {
        this.channel.removeListener(this);
        this.speedChannel.getChannel().removeSpeedListener(this);
        this.channel.addListener(this);
        this.speedChannel.getChannel().addSpeedListener(this);
        this.multipliedSpeed = data.getPatternData().getSpeed();
        this.secondaryMultipliedSpeed = data.getSecondPatternData().getSpeed();
        this.loc = location.clone();
        this.marker = Nightclub.getMarkerFactory().build(this.location, new Color(0, 0, 0), "", 500);
        isZoomed = false;
        buildLasers();
        isLoaded = true;
    }

    public void unload() {
        this.channel.removeListener(this);
        this.speedChannel.getChannel().removeSpeedListener(this);
        off(new Color(0x000000));
        stop();
        isLoaded = false;
    }

    public void debug(boolean on) {
        marker.setName(on ? this.toString() : "");
        marker.setDuration(on ? 3600000 : 5000);
    }

    /**
     * Starts the movement runnable of this Light. This Light will be completely stationary if it is not started before being turned on.
     */
    public void start() {
        stop();
        executorService = Executors.newScheduledThreadPool(1);
        executorService.scheduleAtFixedRate(run, 1, DELAY, TimeUnit.MILLISECONDS);
    }

    /**
     * Stops the movement runnable of this Light.
     */
    public void stop() {
        executorService.shutdownNow();
    }

    /**
     * Stops all current LaserWrapper's and (re-)builds them. Use when changing pattern, pitch, yaw, location, LightType, lightCount.
     * If you want to turn them back on, call on()
     */
    public void buildLasers() {
        for (LaserWrapper lsr : lasers) {
            lsr.stop();
        }
        isOn = false;
        lasers.clear();
        for (int i = 0; i < data.getLightCount(); i++) {
            LaserWrapper laser;
            double separated = 0 + (100.0 / data.getLightCount()) * i;
            Vector3D v = new Vector3D(Math.toRadians(this.location.getYaw()), Math.toRadians(this.location.getPitch())).normalize().scalarMultiply(data.getMaxLength() * data.getOnLength() / 100.0);
            Rotation r = new Rotation(v, this.data.getPatternData().getRotation(), RotationConvention.FRAME_TRANSFORM);
            Rotation r2 = new Rotation(v, this.data.getSecondPatternData().getRotation(), RotationConvention.FRAME_TRANSFORM);
            Vector3D v2 = data.getPatternData().getPattern().apply(v, separated, r, data.getPatternData().getPatternSizeMultiplier() * (data.getOnLength() / 100));
            Vector3D v3 = data.getSecondPatternData().getPattern().apply(v, 0.0, r2, data.getSecondPatternData().getPatternSizeMultiplier() * (data.getOnLength() / 100));
            Vector3D v4 = v.add(v3).add(v2);
            if (data.isFlipStartAndEnd()) {
                laser = Nightclub.getLaserFactory().build(location.clone().add(v4.getX(), v4.getZ(), v4.getY()), location, -1, 256, type);
            } else {
                laser = Nightclub.getLaserFactory().build(location, location.clone().add(v4.getX(), v4.getZ(), v4.getY()), -1, 256, type);
            }
            lasers.add(laser);
        }
    }

    /**
     * Turns Light on, sets length to onLength and sets timeToFade to 0
     */
    public void on(Color color, @Nullable JsonArray lightIDs) {
        if (!isLoaded) return;
        // check if light event is referencing any light ids in the lightid list
        if (lightIDs != null && !data.getLightIDs().isEmpty() && StreamSupport.stream(lightIDs.spliterator(), true).noneMatch(id -> data.getLightIDs().contains(id.getAsInt())))
            return;
        lasers.forEach(LaserWrapper::start);
        if (length < data.getOnLength() && !isOn) {
            length = data.getOnLength();
        }
        length = Math.max(data.getOnLength() * (color.getAlpha() / 255.0), 0.05);
        isOn = true;
        timeToFade = 0;
        marker.setLocation(loc);
        marker.setColor(color);
        marker.start(256);
    }

    public void on(Color color) {
        on(color, null);
    }

    /**
     * Turns Light off, sets length to 0.1 and sets timeToFade to 0
     */
    public void off(Color color, @Nullable JsonArray lightIDs) {
        if (!isLoaded) return;
        // check if light event is referencing any light ids in the lightid list
        if (lightIDs != null && !data.getLightIDs().isEmpty() && StreamSupport.stream(lightIDs.spliterator(), true).noneMatch(id -> data.getLightIDs().contains(id.getAsInt())))
            return;
        marker.stop();
        lasers.forEach(LaserWrapper::stop);
        isOn = false;
        timeToFade = 0;
    }

    public void off(Color color) {
        off(color, null);
    }

    /**
     * Flashes light in a similar way to beat saber, simulating brightness with a longer beam
     */
    public void flash(Color color, @Nullable JsonArray lightIDs) {
        if (!isLoaded) return;
        // check if light event is referencing any light ids in the lightid list
        if (lightIDs != null && !data.getLightIDs().isEmpty() && StreamSupport.stream(lightIDs.spliterator(), true).noneMatch(id -> data.getLightIDs().contains(id.getAsInt())))
            return;
        if (isOn) {
            length = data.getOnLength() * (color.getAlpha() / 255.0);
            length += (100 - data.getOnLength()) / 3;
            timeToFade += 3;
            lasers.forEach(LaserWrapper::changeColor);
            marker.setLocation(loc);
            marker.setColor(color);
            marker.start(256);
        } else {
            flashOff(color);
        }
    }

    public void flash(Color color) {
        flash(color, null);
    }

    /**
     * Flashes light in a similar way to beat saber, simulating brightness with a longer beam and then fades to black
     */
    public void flashOff(Color color, @Nullable JsonArray lightIDs) {
        if (!isLoaded) return;
        // check if light event is referencing any light ids in the lightid list
        if (lightIDs != null && !data.getLightIDs().isEmpty() && StreamSupport.stream(lightIDs.spliterator(), true).noneMatch(id -> data.getLightIDs().contains(id.getAsInt())))
            return;
        on(color);
        flash(color);
        marker.setLocation(loc);
        marker.setColor(color);
        marker.start(256);
        timeToFade = data.getTimeToFadeToBlack();
    }

    public void flashOff(Color color) {
        flashOff(color, null);
    }

    public void ringZoom() {
        if (!isLoaded) return;
        isZoomed = !isZoomed;
        marker.setLocation(loc);
        marker.start(256);
    }

    /**
     * Set which LightChannel this Light should be listening to.
     *
     * @param channel LightChannel to listen to
     */
    public void setChannel(LightChannel channel) {
        this.channel.removeListener(this);
        channel.addListener(this);
        this.channel = channel;
    }

    /**
     *
     */
    public void setSpeedChannel(LightSpeedChannel speedChannel) {
        this.speedChannel.getChannel().removeSpeedListener(this);
        speedChannel.getChannel().addSpeedListener(this);
        this.speedChannel = speedChannel;
    }

    /**
     * Set speed before it is internally multiplied by LightEvents.
     *
     * @param speed Base Speed before multiplier
     */
    public void setBaseSpeed(double speed) {
        data.getPatternData().setSpeed(speed);
        multipliedSpeed = speed;
    }

    public void setSecondaryBaseSpeed(double speed) {
        data.getSecondPatternData().setSpeed(speed);
        secondaryMultipliedSpeed = speed;
    }

    /**
     * Set speed with LightEvent-specified multiplier
     */
    public void setSpeed(double multiplier) {
        if (!isLoaded) return;
        if (multipliedSpeed == 0 && multiplier > 0) {
            x = data.getPatternData().getStartX() * 1.2 + 10;
            x2 = data.getSecondPatternData().getStartX() * 1.2 + 10;
        }
        if (this.multipliedSpeed == data.getPatternData().getSpeed() * multiplier) { // laser "reset"
            x = (x + 12) % 100;
            x2 = (x2 + 12) % 100;
        }
        if (multiplier == 0) {
            x = data.getPatternData().getStartX();
            x2 = data.getSecondPatternData().getStartX();
        }
        this.multipliedSpeed = data.getPatternData().getSpeed() * multiplier;
        this.secondaryMultipliedSpeed = data.getSecondPatternData().getSpeed() * multiplier;
        if (multipliedSpeed >= 20.0) {
            multipliedSpeed = 20.0;
            if (secondaryMultipliedSpeed >= 20.0) {
                secondaryMultipliedSpeed = 20.0;
            }
        }
        run.run();
    }

    private double getMaxLengthPercent() {
        return data.getMaxLength() * length / 100.0;
    }

    public void setType(LightType type) {
        this.type = type;
        buildLasers();
    }

    public String toString() {
        return """
                name="this.name
                ", channel="this.channel
                ", speedChannel="this.speedChannel
                ", lightIDs="lightid
                """
                .replace("this.name", "" + this.name)
                .replace("this.channel", "" + this.channel)
                .replace("this.speedChannel", "" + this.speedChannel)
                .replace("lightid", "" + data.getLightIDs());
    }
}
