package us.daconta.xlmeco.provider;

public class MetadataValueConverter {

    public static Object toJavaObject(us.daconta.xlmeco.grpc.MetadataValue value) {
        switch (value.getValueCase()) {
            case STRING_VALUE:
                return value.getStringValue();
            case INT_VALUE:
                return value.getIntValue();
            case FLOAT_VALUE:
                return value.getFloatValue();
            default:
                return null;
        }
    }

    public static us.daconta.xlmeco.grpc.MetadataValue fromJavaObject(Object obj) {
        us.daconta.xlmeco.grpc.MetadataValue.Builder builder = us.daconta.xlmeco.grpc.MetadataValue.newBuilder();
        if (obj instanceof String) {
            builder.setStringValue((String) obj);
        } else if (obj instanceof Integer) {
            builder.setIntValue((Integer) obj);
        } else if (obj instanceof Float || obj instanceof Double) {
            builder.setFloatValue(((Number) obj).floatValue());
        }
        return builder.build();
    }
}
