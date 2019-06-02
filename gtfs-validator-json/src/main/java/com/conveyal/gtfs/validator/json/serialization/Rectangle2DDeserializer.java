package com.conveyal.gtfs.validator.json.serialization;

import java.awt.geom.Rectangle2D;
import java.io.IOException;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

/**
 * From https://github.com/conveyal/gtfs-data-manager/blob/master/app/controllers/api/Rectangle2DDeserializer.java
 */
public class Rectangle2DDeserializer extends JsonDeserializer<Rectangle2D> {

    @Override
    public Rectangle2D deserialize(JsonParser jp, DeserializationContext arg1)
            throws IOException, JsonProcessingException {

        IntermediateBoundingBox bbox = jp.readValueAs(IntermediateBoundingBox.class);

        if (bbox.north == null || bbox.south == null || bbox.east == null || bbox.west == null)
            throw new JsonParseException("Unable to deserialize bounding box; need north, south, east, and west.", jp.getCurrentLocation());

        Rectangle2D.Double ret = new Rectangle2D.Double(bbox.west, bbox.north, 0, 0);
        ret.add(bbox.east, bbox.south);
        return ret;
    }

    /**
     * A place to hold information from the JSON stream temporarily.
     */
    private static class IntermediateBoundingBox {
        public Double north;
        public Double south;
        public Double east;
        public Double west;
    }

}
