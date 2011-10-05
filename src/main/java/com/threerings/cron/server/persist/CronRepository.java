//
// $Id$

package com.threerings.cron.server.persist;

import java.util.Set;

import com.google.inject.Inject;

import com.samskivert.depot.DepotRepository;
import com.samskivert.depot.DuplicateKeyException;
import com.samskivert.depot.Ops;
import com.samskivert.depot.PersistenceContext;
import com.samskivert.depot.PersistentRecord;
import com.samskivert.depot.clause.Where;

/**
 * Manages our cron related database tables.
 */
public class CronRepository extends DepotRepository
{
    @Inject public CronRepository (PersistenceContext ctx)
    {
        super(ctx);
    }

    /**
     * Claims the specified job for this server for the specified execution time. The job will also
     * be marked as having been run for the time in question in the process.
     *
     * @return true if this server modified the record and was thus granted the job, false if
     * another server beat us to the punch.
     */
    public boolean claimJob (String ident, int dayOfYear, int minuteOfDay)
    {
        // if the record doesn't yet exist, we need to insert a new record
        if (load(CronRecord.getKey(ident)) == null) {
            try {
                CronRecord record = new CronRecord();
                record.ident = ident;
                record.lastExecutedDay = dayOfYear;
                record.lastExecutedMinute = minuteOfDay;
                insert(record);
                return true;
            } catch (DuplicateKeyException dke) {
                return false; // beaten to the punch
            }
        }

        // otherwise we can update the existing record and if our update goes through, we have
        // claimed the cron job for this particular execution
        return updatePartial(
            CronRecord.class,
            new Where(Ops.and(CronRecord.IDENT.eq(ident),
                              Ops.or(CronRecord.LAST_EXECUTED_DAY.notEq(dayOfYear),
                                     CronRecord.LAST_EXECUTED_MINUTE.notEq(minuteOfDay)))),
            CronRecord.getKey(ident),
            CronRecord.LAST_EXECUTED_DAY, dayOfYear,
            CronRecord.LAST_EXECUTED_MINUTE, minuteOfDay) == 1;
    }

    @Override // from DepotRepository
    protected void getManagedRecords (Set<Class<? extends PersistentRecord>> classes)
    {
        classes.add(CronRecord.class);
    }
}
