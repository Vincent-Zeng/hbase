/**
 * Autogenerated by Thrift Compiler (0.12.0)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */
package org.apache.hadoop.hbase.thrift2.generated;

@SuppressWarnings({"cast", "rawtypes", "serial", "unchecked", "unused"})
@javax.annotation.Generated(value = "Autogenerated by Thrift Compiler (0.12.0)", date = "2019-02-22")
public class TCellVisibility implements org.apache.thrift.TBase<TCellVisibility, TCellVisibility._Fields>, java.io.Serializable, Cloneable, Comparable<TCellVisibility> {
  private static final org.apache.thrift.protocol.TStruct STRUCT_DESC = new org.apache.thrift.protocol.TStruct("TCellVisibility");

  private static final org.apache.thrift.protocol.TField EXPRESSION_FIELD_DESC = new org.apache.thrift.protocol.TField("expression", org.apache.thrift.protocol.TType.STRING, (short)1);

  private static final org.apache.thrift.scheme.SchemeFactory STANDARD_SCHEME_FACTORY = new TCellVisibilityStandardSchemeFactory();
  private static final org.apache.thrift.scheme.SchemeFactory TUPLE_SCHEME_FACTORY = new TCellVisibilityTupleSchemeFactory();

  public @org.apache.thrift.annotation.Nullable java.lang.String expression; // optional

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements org.apache.thrift.TFieldIdEnum {
    EXPRESSION((short)1, "expression");

    private static final java.util.Map<java.lang.String, _Fields> byName = new java.util.HashMap<java.lang.String, _Fields>();

    static {
      for (_Fields field : java.util.EnumSet.allOf(_Fields.class)) {
        byName.put(field.getFieldName(), field);
      }
    }

    /**
     * Find the _Fields constant that matches fieldId, or null if its not found.
     */
    @org.apache.thrift.annotation.Nullable
    public static _Fields findByThriftId(int fieldId) {
      switch(fieldId) {
        case 1: // EXPRESSION
          return EXPRESSION;
        default:
          return null;
      }
    }

    /**
     * Find the _Fields constant that matches fieldId, throwing an exception
     * if it is not found.
     */
    public static _Fields findByThriftIdOrThrow(int fieldId) {
      _Fields fields = findByThriftId(fieldId);
      if (fields == null) throw new java.lang.IllegalArgumentException("Field " + fieldId + " doesn't exist!");
      return fields;
    }

    /**
     * Find the _Fields constant that matches name, or null if its not found.
     */
    @org.apache.thrift.annotation.Nullable
    public static _Fields findByName(java.lang.String name) {
      return byName.get(name);
    }

    private final short _thriftId;
    private final java.lang.String _fieldName;

    _Fields(short thriftId, java.lang.String fieldName) {
      _thriftId = thriftId;
      _fieldName = fieldName;
    }

    public short getThriftFieldId() {
      return _thriftId;
    }

    public java.lang.String getFieldName() {
      return _fieldName;
    }
  }

  // isset id assignments
  private static final _Fields optionals[] = {_Fields.EXPRESSION};
  public static final java.util.Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> metaDataMap;
  static {
    java.util.Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> tmpMap = new java.util.EnumMap<_Fields, org.apache.thrift.meta_data.FieldMetaData>(_Fields.class);
    tmpMap.put(_Fields.EXPRESSION, new org.apache.thrift.meta_data.FieldMetaData("expression", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRING)));
    metaDataMap = java.util.Collections.unmodifiableMap(tmpMap);
    org.apache.thrift.meta_data.FieldMetaData.addStructMetaDataMap(TCellVisibility.class, metaDataMap);
  }

  public TCellVisibility() {
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public TCellVisibility(TCellVisibility other) {
    if (other.isSetExpression()) {
      this.expression = other.expression;
    }
  }

  public TCellVisibility deepCopy() {
    return new TCellVisibility(this);
  }

  @Override
  public void clear() {
    this.expression = null;
  }

  @org.apache.thrift.annotation.Nullable
  public java.lang.String getExpression() {
    return this.expression;
  }

  public TCellVisibility setExpression(@org.apache.thrift.annotation.Nullable java.lang.String expression) {
    this.expression = expression;
    return this;
  }

  public void unsetExpression() {
    this.expression = null;
  }

  /** Returns true if field expression is set (has been assigned a value) and false otherwise */
  public boolean isSetExpression() {
    return this.expression != null;
  }

  public void setExpressionIsSet(boolean value) {
    if (!value) {
      this.expression = null;
    }
  }

  public void setFieldValue(_Fields field, @org.apache.thrift.annotation.Nullable java.lang.Object value) {
    switch (field) {
    case EXPRESSION:
      if (value == null) {
        unsetExpression();
      } else {
        setExpression((java.lang.String)value);
      }
      break;

    }
  }

  @org.apache.thrift.annotation.Nullable
  public java.lang.Object getFieldValue(_Fields field) {
    switch (field) {
    case EXPRESSION:
      return getExpression();

    }
    throw new java.lang.IllegalStateException();
  }

  /** Returns true if field corresponding to fieldID is set (has been assigned a value) and false otherwise */
  public boolean isSet(_Fields field) {
    if (field == null) {
      throw new java.lang.IllegalArgumentException();
    }

    switch (field) {
    case EXPRESSION:
      return isSetExpression();
    }
    throw new java.lang.IllegalStateException();
  }

  @Override
  public boolean equals(java.lang.Object that) {
    if (that == null)
      return false;
    if (that instanceof TCellVisibility)
      return this.equals((TCellVisibility)that);
    return false;
  }

  public boolean equals(TCellVisibility that) {
    if (that == null)
      return false;
    if (this == that)
      return true;

    boolean this_present_expression = true && this.isSetExpression();
    boolean that_present_expression = true && that.isSetExpression();
    if (this_present_expression || that_present_expression) {
      if (!(this_present_expression && that_present_expression))
        return false;
      if (!this.expression.equals(that.expression))
        return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    int hashCode = 1;

    hashCode = hashCode * 8191 + ((isSetExpression()) ? 131071 : 524287);
    if (isSetExpression())
      hashCode = hashCode * 8191 + expression.hashCode();

    return hashCode;
  }

  @Override
  public int compareTo(TCellVisibility other) {
    if (!getClass().equals(other.getClass())) {
      return getClass().getName().compareTo(other.getClass().getName());
    }

    int lastComparison = 0;

    lastComparison = java.lang.Boolean.valueOf(isSetExpression()).compareTo(other.isSetExpression());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetExpression()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.expression, other.expression);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    return 0;
  }

  @org.apache.thrift.annotation.Nullable
  public _Fields fieldForId(int fieldId) {
    return _Fields.findByThriftId(fieldId);
  }

  public void read(org.apache.thrift.protocol.TProtocol iprot) throws org.apache.thrift.TException {
    scheme(iprot).read(iprot, this);
  }

  public void write(org.apache.thrift.protocol.TProtocol oprot) throws org.apache.thrift.TException {
    scheme(oprot).write(oprot, this);
  }

  @Override
  public java.lang.String toString() {
    java.lang.StringBuilder sb = new java.lang.StringBuilder("TCellVisibility(");
    boolean first = true;

    if (isSetExpression()) {
      sb.append("expression:");
      if (this.expression == null) {
        sb.append("null");
      } else {
        sb.append(this.expression);
      }
      first = false;
    }
    sb.append(")");
    return sb.toString();
  }

  public void validate() throws org.apache.thrift.TException {
    // check for required fields
    // check for sub-struct validity
  }

  private void writeObject(java.io.ObjectOutputStream out) throws java.io.IOException {
    try {
      write(new org.apache.thrift.protocol.TCompactProtocol(new org.apache.thrift.transport.TIOStreamTransport(out)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }

  private void readObject(java.io.ObjectInputStream in) throws java.io.IOException, java.lang.ClassNotFoundException {
    try {
      read(new org.apache.thrift.protocol.TCompactProtocol(new org.apache.thrift.transport.TIOStreamTransport(in)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }

  private static class TCellVisibilityStandardSchemeFactory implements org.apache.thrift.scheme.SchemeFactory {
    public TCellVisibilityStandardScheme getScheme() {
      return new TCellVisibilityStandardScheme();
    }
  }

  private static class TCellVisibilityStandardScheme extends org.apache.thrift.scheme.StandardScheme<TCellVisibility> {

    public void read(org.apache.thrift.protocol.TProtocol iprot, TCellVisibility struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TField schemeField;
      iprot.readStructBegin();
      while (true)
      {
        schemeField = iprot.readFieldBegin();
        if (schemeField.type == org.apache.thrift.protocol.TType.STOP) { 
          break;
        }
        switch (schemeField.id) {
          case 1: // EXPRESSION
            if (schemeField.type == org.apache.thrift.protocol.TType.STRING) {
              struct.expression = iprot.readString();
              struct.setExpressionIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          default:
            org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
        }
        iprot.readFieldEnd();
      }
      iprot.readStructEnd();

      // check for required fields of primitive type, which can't be checked in the validate method
      struct.validate();
    }

    public void write(org.apache.thrift.protocol.TProtocol oprot, TCellVisibility struct) throws org.apache.thrift.TException {
      struct.validate();

      oprot.writeStructBegin(STRUCT_DESC);
      if (struct.expression != null) {
        if (struct.isSetExpression()) {
          oprot.writeFieldBegin(EXPRESSION_FIELD_DESC);
          oprot.writeString(struct.expression);
          oprot.writeFieldEnd();
        }
      }
      oprot.writeFieldStop();
      oprot.writeStructEnd();
    }

  }

  private static class TCellVisibilityTupleSchemeFactory implements org.apache.thrift.scheme.SchemeFactory {
    public TCellVisibilityTupleScheme getScheme() {
      return new TCellVisibilityTupleScheme();
    }
  }

  private static class TCellVisibilityTupleScheme extends org.apache.thrift.scheme.TupleScheme<TCellVisibility> {

    @Override
    public void write(org.apache.thrift.protocol.TProtocol prot, TCellVisibility struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TTupleProtocol oprot = (org.apache.thrift.protocol.TTupleProtocol) prot;
      java.util.BitSet optionals = new java.util.BitSet();
      if (struct.isSetExpression()) {
        optionals.set(0);
      }
      oprot.writeBitSet(optionals, 1);
      if (struct.isSetExpression()) {
        oprot.writeString(struct.expression);
      }
    }

    @Override
    public void read(org.apache.thrift.protocol.TProtocol prot, TCellVisibility struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TTupleProtocol iprot = (org.apache.thrift.protocol.TTupleProtocol) prot;
      java.util.BitSet incoming = iprot.readBitSet(1);
      if (incoming.get(0)) {
        struct.expression = iprot.readString();
        struct.setExpressionIsSet(true);
      }
    }
  }

  private static <S extends org.apache.thrift.scheme.IScheme> S scheme(org.apache.thrift.protocol.TProtocol proto) {
    return (org.apache.thrift.scheme.StandardScheme.class.equals(proto.getScheme()) ? STANDARD_SCHEME_FACTORY : TUPLE_SCHEME_FACTORY).getScheme();
  }
}

