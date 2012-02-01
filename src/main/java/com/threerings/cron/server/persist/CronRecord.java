//
// ooo-cron - a library for multiserver-coordinated cron job execution
// Copyright (c) 2010-2012, Three Rings Design, Inc. - All rights reserved.
// http://github.com/threerings/ooo-cron/blob/master/etc/LICENSE

package com.threerings.cron.server.persist;

import com.samskivert.depot.Key;
import com.samskivert.depot.PersistentRecord;
import com.samskivert.depot.expression.ColumnExp;
import com.samskivert.depot.annotation.Id;

/**
 * Used to track and coordinate cron job executions.
 */
public class CronRecord extends PersistentRecord
{
    // AUTO-GENERATED: FIELDS START
    public static final Class<CronRecord> _R = CronRecord.class;
    public static final ColumnExp<String> IDENT = colexp(_R, "ident");
    public static final ColumnExp<Integer> LAST_EXECUTED_DAY = colexp(_R, "lastExecutedDay");
    public static final ColumnExp<Integer> LAST_EXECUTED_MINUTE = colexp(_R, "lastExecutedMinute");
    // AUTO-GENERATED: FIELDS END

    /** Update this value if you make schema-changing modifications to this record. */
    public static final int SCHEMA_VERSION = 1;

    /** The string identifier for this job. */
    @Id public String ident;

    /** The day of the year at which the job was last executed. */
    public int lastExecutedDay;

    /** The minute of the day at which the job was last executed. */
    public int lastExecutedMinute;

    // AUTO-GENERATED: METHODS START
    /**
     * Create and return a primary {@link Key} to identify a {@link CronRecord}
     * with the supplied key values.
     */
    public static Key<CronRecord> getKey (String ident)
    {
        return newKey(_R, ident);
    }

    /** Register the key fields in an order matching the getKey() factory. */
    static { registerKeyFields(IDENT); }
    // AUTO-GENERATED: METHODS END
}
