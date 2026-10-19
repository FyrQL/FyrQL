/*
 * Copyright 2026 the FyrQL authors
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * This Source Code Form is "Incompatible With Secondary Licenses", as
 * defined by the Mozilla Public License, v. 2.0.
 */

package net.fyrql.connector.json;

import net.fyrql.core.function.DateFormats;

import java.util.Objects;

/**
 * How JSON input is read, with the options and defaults of DuckDB's
 * {@code read_json}.
 *
 * @param format how the records are laid out
 * @param sampleSize the number of records the schema is inferred from, or
 *        -1 for all of them; 20480 by default
 * @param mapInferenceThreshold the number of keys from which an object
 *        whose values have one type becomes a {@code MAP} instead of a
 *        {@code STRUCT}, or -1 for never; 200 by default
 * @param fieldAppearanceThreshold the share of the objects of a field below
 *        which a key appears on average, so that the objects become a
 *        {@code MAP}; 0.1 by default
 * @param ignoreErrors whether a value that does not fit its type is read as
 *        {@code NULL} and an unknown key is skipped, instead of failing
 * @param dateFormat the only format, of {@code strptime}, in which a string
 *        is taken for a {@code DATE}, or {@code null} for the formats DuckDB
 *        tries
 * @param timestampFormat the same for a {@code TIMESTAMP}
 */
public record JsonOptions(JsonFormat format, int sampleSize, int mapInferenceThreshold,
        double fieldAppearanceThreshold, boolean ignoreErrors, String dateFormat, String timestampFormat) {

    /** The options of DuckDB's {@code read_json} by default. */
    public static final JsonOptions DEFAULT = new JsonOptions(JsonFormat.AUTO, 20480, 200, 0.1, false, null, null);

    public JsonOptions {
        Objects.requireNonNull(format, "format");
        if (sampleSize != -1 && sampleSize < 1) {
            throw new IllegalArgumentException("The sample_size must be -1 or positive: " + sampleSize);
        }
        if (mapInferenceThreshold < -1) {
            throw new IllegalArgumentException("The map_inference_threshold must be -1 or at least 0: "
                    + mapInferenceThreshold);
        }
        if (!(fieldAppearanceThreshold >= 0 && fieldAppearanceThreshold <= 1)) {
            throw new IllegalArgumentException("The field_appearance_threshold must be from 0 to 1: "
                    + fieldAppearanceThreshold);
        }
        if (dateFormat != null) {
            DateFormats.check(dateFormat);
        }
        if (timestampFormat != null) {
            DateFormats.check(timestampFormat);
        }
    }

    public JsonOptions withFormat(JsonFormat format) {
        return new JsonOptions(format, sampleSize, mapInferenceThreshold, fieldAppearanceThreshold, ignoreErrors,
                dateFormat, timestampFormat);
    }

    public JsonOptions withSampleSize(int sampleSize) {
        return new JsonOptions(format, sampleSize, mapInferenceThreshold, fieldAppearanceThreshold, ignoreErrors,
                dateFormat, timestampFormat);
    }

    public JsonOptions withMapInferenceThreshold(int mapInferenceThreshold) {
        return new JsonOptions(format, sampleSize, mapInferenceThreshold, fieldAppearanceThreshold, ignoreErrors,
                dateFormat, timestampFormat);
    }

    public JsonOptions withFieldAppearanceThreshold(double fieldAppearanceThreshold) {
        return new JsonOptions(format, sampleSize, mapInferenceThreshold, fieldAppearanceThreshold, ignoreErrors,
                dateFormat, timestampFormat);
    }

    public JsonOptions withIgnoreErrors(boolean ignoreErrors) {
        return new JsonOptions(format, sampleSize, mapInferenceThreshold, fieldAppearanceThreshold, ignoreErrors,
                dateFormat, timestampFormat);
    }

    public JsonOptions withDateFormat(String dateFormat) {
        return new JsonOptions(format, sampleSize, mapInferenceThreshold, fieldAppearanceThreshold, ignoreErrors,
                dateFormat, timestampFormat);
    }

    public JsonOptions withTimestampFormat(String timestampFormat) {
        return new JsonOptions(format, sampleSize, mapInferenceThreshold, fieldAppearanceThreshold, ignoreErrors,
                dateFormat, timestampFormat);
    }
}
