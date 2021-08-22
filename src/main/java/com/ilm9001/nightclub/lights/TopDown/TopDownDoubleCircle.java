package com.ilm9001.nightclub.lights.TopDown;

import com.ilm9001.nightclub.Nightclub;
import com.ilm9001.nightclub.lights.Circler;
import com.ilm9001.nightclub.lights.Directions;
import com.ilm9001.nightclub.lights.LightAbstract;
import com.ilm9001.nightclub.util.LaserWrapper;
import org.apache.commons.math3.geometry.euclidean.threed.Vector3D;
import org.bukkit.Location;

public class TopDownDoubleCircle extends LightAbstract {
    Circler c;
    Circler c2;
    double angleSeperation;
    
    public TopDownDoubleCircle(Location anchor, int num_lsr) {
        this(anchor,num_lsr,true);
    }
    public TopDownDoubleCircle(Location anchor, int num_lsr,boolean rotation) {
        super(anchor,num_lsr);
        if(rotation) c = LightAbstract.cN;
        else c = LightAbstract.cNAC;
        if(rotation) c2 = LightAbstract.cO;
        else c2 = LightAbstract.cOAC;
        angleSeperation = 360.0/num_lsr;
        TDDCRun run = new TDDCRun();
        run.runTaskTimerAsynchronously(Nightclub.getInstance(),20,2);
    }
    
    @Override
    public void setSpeed(int multiplier) {
        c.setSpeed(c.getInitialSpeed()*multiplier);
        c2.setSpeed(c2.getInitialSpeed()*multiplier);
    }
    
    class TDDCRun extends Run {
        public void lights() {
            double a_seperated = c.getDegrees();
            double b_seperated = c2.getDegrees();
            for (LaserWrapper lsr : lsr) {
                Vector3D v1 = new Vector3D((Directions.SOUTH.getValue() * a_seperated) / 360.0, 0).normalize().scalarMultiply(len/1.3);
                Vector3D v2 = new Vector3D((Directions.SOUTH.getValue() * b_seperated) / 360.0,0).normalize().scalarMultiply(len/4.8);
                Location firstiteration = anchor.clone().add(v1.getX(), v1.getZ() - len, v1.getY());
                lsr.setEnd(firstiteration.clone().add(v2.getX(), v2.getZ(), v2.getY()));
                a_seperated += angleSeperation;
                b_seperated += angleSeperation;
            }
        }
    }
}

