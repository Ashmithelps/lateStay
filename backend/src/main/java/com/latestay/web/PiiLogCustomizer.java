package com.latestay.web;

import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Applied to every JSON log line in production (logging.structured.json.customizer): masks phone numbers in any
 * string field, including messages and stack traces (a database error can quote a whole row).
 */
public class PiiLogCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {

    @Override
    public void customize(JsonWriter.Members<Object> members) {
        members.applyingValueProcessor(JsonWriter.ValueProcessor.of(String.class, Pii::scrub));
    }
}
