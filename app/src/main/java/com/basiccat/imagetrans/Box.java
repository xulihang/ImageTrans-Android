package com.basiccat.imagetrans;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A text region in image coordinates. Mirrors the box format used by the
 * ImageTrans server / browser extension: x, y, width, height plus source and
 * target text.
 */
public class Box {
    public double x;
    public double y;
    public double width;
    public double height;
    public String source = "";
    public String target = "";

    public Box() {
    }

    public Box(double x, double y, double width, double height, String source) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.source = source == null ? "" : source;
        this.target = "";
    }

    public double left() {
        return x;
    }

    public double top() {
        return y;
    }

    public double right() {
        return x + width;
    }

    public double bottom() {
        return y + height;
    }

    public double centerX() {
        return x + width / 2.0;
    }

    public double centerY() {
        return y + height / 2.0;
    }

    public boolean hasText() {
        return (source != null && !source.trim().isEmpty())
                || (target != null && !target.trim().isEmpty());
    }

    public JSONObject toJson() throws JSONException {
        JSONObject geometry = new JSONObject();
        geometry.put("X", x);
        geometry.put("Y", y);
        geometry.put("width", width);
        geometry.put("height", height);

        JSONObject object = new JSONObject();
        object.put("geometry", geometry);
        object.put("source", source == null ? "" : source);
        object.put("target", target == null ? "" : target);
        return object;
    }

    @Override
    public String toString() {
        return "Box{" + x + "," + y + " " + width + "x" + height
                + ", source='" + source + "', target='" + target + "'}";
    }
}
