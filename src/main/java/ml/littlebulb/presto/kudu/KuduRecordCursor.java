/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ml.littlebulb.presto.kudu;

import com.facebook.presto.spi.RecordCursor;
import com.facebook.presto.spi.type.Type;
import io.airlift.log.Logger;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import org.apache.kudu.client.KuduException;
import org.apache.kudu.client.KuduScanner;
import org.apache.kudu.client.RowResult;
import org.apache.kudu.client.RowResultIterator;

import java.lang.reflect.Field;
import java.util.List;

import static java.lang.Float.floatToRawIntBits;

public class KuduRecordCursor implements RecordCursor {

    private static final Logger log = Logger.get(KuduRecordCursor.class);

    private final KuduScanner scanner;
    private final List<KuduType> columnTypes;
    private final Field rowDataField;
    private RowResultIterator nextRows;
    protected RowResult currentRow;

    private long totalBytes;
    private long nanoStart;
    private long nanoEnd;
    private boolean started;

    public KuduRecordCursor(KuduScanner scanner, List<KuduType> columnTypes) {
        this.scanner = scanner;
        this.columnTypes = columnTypes;
        Field field = null;
        try {
            field = RowResult.class.getDeclaredField("rawData");
            field.setAccessible(true);
        } catch (NoSuchFieldException e) {
            // ignore
        }
        this.rowDataField = field;
    }

    @Override
    public long getCompletedBytes() {
        return totalBytes;
    }

    @Override
    public long getReadTimeNanos() {
        return nanoStart > 0L ? (nanoEnd == 0 ? System.nanoTime() : nanoEnd) - nanoStart : 0L;
    }

    @Override
    public Type getType(int field) {
        return columnTypes.get(field).getPrestoType();
    }

    protected int mapping(int field) { return field; }

    /**
     * get next Row/Page
     */
    @Override
    public boolean advanceNextPosition() {
        if (started && nextRows == null) {
            return false;
        }

        boolean needNextRows = !started || !nextRows.hasNext();

        if (!started) {
            started = true;
            nanoStart = System.nanoTime();
        }

        if (needNextRows) {
            try {
                nextRows = scanner.nextRows();
                if (nextRows == null) {
                    currentRow = null;
                    return false;
                }
                log.debug("Fetched " + nextRows.getNumRows() + " rows");
            } catch (KuduException e) {
                currentRow = null;
                throw new RuntimeException(e);
            }
        }

        if (nextRows.hasNext()) {
            currentRow = nextRows.next();
            totalBytes += getRowLength();
            return true;
        }
        currentRow = null;
        return false;
    }

    private org.apache.kudu.util.Slice getCurrentRowRawData() {
        if (rowDataField != null && currentRow != null) {
            try {
                return ((org.apache.kudu.util.Slice) rowDataField.get(currentRow));
            } catch (IllegalAccessException e) {
                return null;
            }
        } else {
            return null;
        }
    }

    private int getRowLength() {
        org.apache.kudu.util.Slice rawData = getCurrentRowRawData();
        if (rawData != null) {
            return rawData.length();
        } else {
            return columnTypes.size();
        }
    }

    @Override
    public boolean getBoolean(int field) {
        return currentRow.getBoolean(mapping(field));
    }

    @Override
    public long getLong(int field) {
        switch (columnTypes.get(field)) {
            case INT64:
                return currentRow.getLong(mapping(field));
            case UNIXTIME_MICROS:
                return currentRow.getLong(mapping(field)) / 1000;
            case INT32:
                return currentRow.getInt(mapping(field));
            case FLOAT:
                return floatToRawIntBits(currentRow.getFloat(mapping(field)));
            case INT16:
                return currentRow.getShort(mapping(field));
            case INT8:
                return currentRow.getByte(mapping(field));
            default:
                throw new IllegalStateException("Unexpected column type: " + columnTypes.get(field));
        }
    }

    @Override
    public double getDouble(int field) {
        return currentRow.getDouble(mapping(field));
    }

    @Override
    public Slice getSlice(int field) {
        final KuduType kuduType = columnTypes.get(field);
        switch (kuduType) {
            case BINARY:
                return Slices.wrappedBuffer(currentRow.getBinary(mapping(field)));
            case STRING:
                return Slices.utf8Slice(currentRow.getString(mapping(field)));
            default:
                throw new IllegalStateException("getSlice for kuduType=" + kuduType);
        }
    }

    @Override
    public Object getObject(int field) {
        return columnTypes.get(field).getFieldValue(currentRow, mapping(field));
    }

    @Override
    public boolean isNull(int field) {
        int mappedField = mapping(field);
        return mappedField >= 0 && currentRow.isNull(mappedField);
    }

    @Override
    public void close() {
        nanoEnd = System.nanoTime();
        currentRow = null;
        nextRows = null;
    }
}
