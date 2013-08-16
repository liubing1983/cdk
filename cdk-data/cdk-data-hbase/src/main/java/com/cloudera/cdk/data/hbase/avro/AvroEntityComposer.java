// (c) Copyright 2011-2013 Cloudera, Inc.
package com.cloudera.cdk.data.hbase.avro;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.avro.Schema;
import org.apache.avro.Schema.Field;
import org.apache.avro.generic.IndexedRecord;

import com.cloudera.cdk.data.hbase.EntityComposer;
import com.cloudera.cdk.data.hbase.EntitySchema.FieldMapping;
import com.cloudera.cdk.data.hbase.HBaseCommonException;
import com.cloudera.cdk.data.hbase.MappingType;
import com.cloudera.cdk.data.hbase.SchemaValidationException;

/**
 * An EntityComposer implementation for Avro records. It will handle both
 * SpecificRecord entities and GenericRecord entities.
 * 
 * @param <E>
 *          The type of the entity
 */
public class AvroEntityComposer<E extends IndexedRecord> implements
    EntityComposer<E> {

  /**
   * The Avro schema for the Avro records this EntityComposer will compose.
   */
  private final AvroEntitySchema avroSchema;

  /**
   * Boolean to indicate whether this is a specific record or generic record
   * composer. TODO: Eventually use an enum type when we support more than two
   * types of Avro records.
   */
  private final boolean specific;

  /**
   * An AvroRecordBuilderFactory that can produce AvroRecordBuilders for this
   * composer to compose Avro entities.
   */
  private final AvroRecordBuilderFactory<E> recordBuilderFactory;

  /**
   * A mapping of entity field names to AvroRecordBuilderFactories for any
   * keyAsColumn mapped fields that are Avro record types. These are needed to
   * get builders that can construct the keyAsColumn field values from their
   * parts.
   */
  private final Map<String, AvroRecordBuilderFactory<E>> kacRecordBuilderFactories;

  /**
   * AvroEntityComposer constructor.
   * 
   * @param avroEntitySchema
   *          The schema for the Avro entities this composer composes.
   * @param specific
   *          True if this composer composes Specific records. Otherwise, it
   *          composes Generic records.
   */
  public AvroEntityComposer(AvroEntitySchema avroEntitySchema, boolean specific) {
    this.avroSchema = avroEntitySchema;
    this.specific = specific;
    this.recordBuilderFactory = buildAvroRecordBuilderFactory(avroEntitySchema
        .getAvroSchema());
    this.kacRecordBuilderFactories = new HashMap<String, AvroRecordBuilderFactory<E>>();
    initRecordBuilderFactories();
  }

  @Override
  public Builder<E> getBuilder() {
    return new Builder<E>() {
      private final AvroRecordBuilder<E> recordBuilder = recordBuilderFactory
          .getBuilder();

      @Override
      public com.cloudera.cdk.data.hbase.EntityComposer.Builder<E> put(
          String fieldName, Object value) {
        recordBuilder.put(fieldName, value);
        return this;
      }

      @Override
      public E build() {
        return recordBuilder.build();
      }
    };
  }

  @Override
  public Object extractField(E entity, String fieldName) {
    Schema schema = avroSchema.getAvroSchema();
    Field field = schema.getField(fieldName);
    if (field == null) {
      throw new SchemaValidationException("No field named " + fieldName
          + " in schema " + schema);
    }
    Object fieldValue = entity.get(field.pos());
    if (fieldValue == null) {
      // if the field value is null, and the field is a primitive type,
      // we should make the field represent java's default type. This
      // can happen when using GenericRecord. SpecificRecord has it's
      // fields represented by members of a class, so a SpecificRecord's
      // primitive fields will never be null. We are doing this so
      // GenericRecord acts like SpecificRecord in this case.
      fieldValue = getDefaultPrimitive(field);
    }
    return fieldValue;
  }

  @SuppressWarnings("unchecked")
  @Override
  public Map<CharSequence, Object> extractKeyAsColumnValues(String fieldName,
      Object fieldValue) {
    Schema schema = avroSchema.getAvroSchema();
    Field field = schema.getField(fieldName);
    if (field == null) {
      throw new SchemaValidationException("No field named " + fieldName
          + " in schema " + schema);
    }
    if (field.schema().getType() == Schema.Type.MAP) {
      return new HashMap<CharSequence, Object>(
          (Map<CharSequence, Object>) fieldValue);
    } else if (field.schema().getType() == Schema.Type.RECORD) {
      Map<CharSequence, Object> keyAsColumnValues = new HashMap<CharSequence, Object>();
      IndexedRecord avroRecord = (IndexedRecord) fieldValue;
      for (Field avroRecordField : avroRecord.getSchema().getFields()) {
        keyAsColumnValues.put(avroRecordField.name(),
            avroRecord.get(avroRecordField.pos()));
      }
      return keyAsColumnValues;
    } else {
      throw new SchemaValidationException(
          "Only MAP or RECORD type valid for keyAsColumn fields. Found "
              + field.schema().getType());
    }
  }

  @Override
  public Object buildKeyAsColumnField(String fieldName,
      Map<String, Object> keyAsColumnValues) {
    Schema schema = avroSchema.getAvroSchema();
    Field field = schema.getField(fieldName);
    if (field == null) {
      throw new SchemaValidationException("No field named " + fieldName
          + " in schema " + schema);
    }

    Schema.Type fieldType = field.schema().getType();
    if (fieldType == Schema.Type.MAP) {
      Map<CharSequence, Object> retMap = new HashMap<CharSequence, Object>();
      for (Entry<String, Object> entry : keyAsColumnValues.entrySet()) {
        retMap.put(entry.getKey(), entry.getValue());
      }
      return retMap;
    } else if (fieldType == Schema.Type.RECORD) {
      AvroRecordBuilder<E> builder = kacRecordBuilderFactories.get(fieldName)
          .getBuilder();
      for (Entry<String, Object> keyAsColumnEntry : keyAsColumnValues
          .entrySet()) {
        builder.put(keyAsColumnEntry.getKey(), keyAsColumnEntry.getValue());
      }
      return builder.build();
    } else {
      throw new SchemaValidationException(
          "Only MAP or RECORD type valid for keyAsColumn fields. Found "
              + fieldType);
    }
  }

  /**
   * Initialize the AvroRecordBuilderFactories for all keyAsColumn mapped fields
   * that are record types. We need to be able to get record builders for these
   * since the records are broken across many columns, and need to be
   * constructed by the composer.
   */
  private void initRecordBuilderFactories() {
    for (FieldMapping fieldMapping : avroSchema.getFieldMappings()) {
      if (fieldMapping.getMappingType() == MappingType.KEY_AS_COLUMN) {
        String fieldName = fieldMapping.getFieldName();
        Schema fieldSchema = avroSchema.getAvroSchema().getField(fieldName)
            .schema();
        Schema.Type fieldSchemaType = fieldSchema.getType();
        if (fieldSchemaType == Schema.Type.RECORD) {
          AvroRecordBuilderFactory<E> factory = buildAvroRecordBuilderFactory(fieldSchema);
          kacRecordBuilderFactories.put(fieldName, factory);
        }
      }
    }
  }

  /**
   * Build the appropriate AvroRecordBuilderFactory for this instance. Avro has
   * many different record types, of which we support two: Specific and Generic.
   * 
   * @param schema
   *          The Avro schema needed to construct the AvroRecordBuilderFactory.
   * @return The constructed AvroRecordBuilderFactory.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private AvroRecordBuilderFactory<E> buildAvroRecordBuilderFactory(
      Schema schema) {
    if (specific) {
      Class<E> specificClass;
      String className = schema.getFullName();
      try {
        specificClass = (Class<E>) Class.forName(className);
      } catch (ClassNotFoundException e) {
        throw new HBaseCommonException("Could not get Class instance for "
            + className);
      }
      return new SpecificAvroRecordBuilderFactory(specificClass);
    } else {
      return (AvroRecordBuilderFactory<E>) new GenericAvroRecordBuilderFactory(
          schema);
    }
  }

  /**
   * Get's the default value for the primitive types. This matches the default
   * Java would assign to the following primitive types:
   * 
   * int, long, boolean, float, and double.
   * 
   * If field is any other type, this method will return null.
   * 
   * @param field
   *          The Schema field
   * @return The default value for the schema field's type, or null if the type
   *         of field is not a primitive type.
   */
  private Object getDefaultPrimitive(Schema.Field field) {
    Schema.Type type = field.schema().getType();
    if (type == Schema.Type.INT) {
      return 0;
    } else if (type == Schema.Type.LONG) {
      return 0L;
    } else if (type == Schema.Type.BOOLEAN) {
      return false;
    } else if (type == Schema.Type.FLOAT) {
      return 0.0f;
    } else if (type == Schema.Type.DOUBLE) {
      return 0.0d;
    } else {
      // not a primitive type, so return null
      return null;
    }
  }
}
