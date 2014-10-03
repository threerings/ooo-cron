//
// ooo-cron - a library for multiserver-coordinated cron job execution
// Copyright (c) 2010-2012, Three Rings Design, Inc. - All rights reserved.
// http://github.com/threerings/ooo-cron/blob/master/etc/LICENSE

package com.threerings.cron.server;

import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.inject.Inject;

import com.samskivert.util.Calendars;
import com.samskivert.util.Interval;
import com.samskivert.util.Lifecycle;
import com.samskivert.util.RandomUtil;
import com.samskivert.util.RunQueue;

import com.threerings.cron.server.persist.CronRepository;

import static com.threerings.cron.Log.log;

/**
 * Provides database coordinated cron services. Multiple servers talking to the same database may
 * schedule the same jobs and this library will ensure that only one server runs the job for any
 * particular invocation. Additionally the libary makes efforts to distribute the execution tasks
 * randomly across all participating servers.
 *
 * <p>Note: these jobs run on individual separate worker threads. Those worker threads are not
 * configured as daemon threads and thus must run to completion before the server can shutdown. No
 * new jobs will be scheduled after the Lifecycle of which this logic is a part is shutdown.</p>
 */
public class CronLogic
{
    /**
     * Either you should subclass CronLogic and annotate your class as &at;Singleton and
     * ensure the appropriate executor is injected to its constructor, or you can use
     * this class directly and bind a constructed instance...
     *
     * <pre>{@code
     *    bind(CronLogic.class).toInstance(new CronLogic(...));
     * }<pre>
     */
    public CronLogic (Lifecycle cycle, final ExecutorService execsvc)
    {
        this(cycle, new RunQueue() {
            @Override public void postRunnable (Runnable r) {
                execsvc.execute(r);
            }
            @Override public boolean isDispatchThread () {
                return false; // we know this isn't needed for our purposes
            }
            @Override public boolean isRunning () {
                return !execsvc.isShutdown();
            }
        });
    }

    /**
     * Either you should subclass CronLogic and annotate your class as &at;Singleton and
     * ensure the appropriate runqueue is injected to its constructor, or you can use
     * this class directly and bind a constructed instance...
     *
     * <pre>{@code
     *    bind(CronLogic.class).toInstance(new CronLogic(...));
     * }<pre>
     */
    public CronLogic (Lifecycle cycle, RunQueue runQueue)
    {
        _ticker = new JobTicker(runQueue);
        cycle.addComponent(new Lifecycle.Component() {
            public void init () {
                // schedule our ticker to start running at 0 milliseconds after the minute; we'll
                // randomize from there, but this reduces any initial bias
                Calendar cal = Calendar.getInstance();
                long curmils = cal.get(Calendar.SECOND) * 1000L + cal.get(Calendar.MILLISECOND);
                _ticker.schedule(60 * 1000L - curmils);
            }
            public void shutdown () {
                _ticker.cancel();
            }
        });
    }

    /**
     * Schedules a job every N hours. We schedule the job at midnight and then again every N hours
     * for the rest of the day. This means that if N does not evenly divide 24, there may be a gap
     * smaller than N between the last job of the day and 00:00 of the next day.
     *
     * <p> Note that the job will be scheduled to run at some arbitrary minute after the our based
     * on the hashcode of the name of the job identifier.
     *
     * <p> Your job will be run on a separate non-daemon thread, therefore the behavior on shutdown
     * must be taken into consideration for very long-running jobs.
     *
     * @param hourlyPeriod the number of hours between executions of this job.
     * @param ident a unique identifier that differentiates this job from all others scheduled on
     * the same database.
     * @param job a runnable that will be executed periodically <em>on a separate thread</em>.
     */
    public void scheduleEvery (int hourlyPeriod, String ident, Runnable job)
    {
        int minOfHour = Math.abs(ident.hashCode()) % HOUR;
        int minOfDay = 0;
        synchronized (_jobs) {
            while (minOfDay < 24*HOUR) {
                _jobs.put(minOfDay + minOfHour, new Job(ident, job));
                minOfDay += hourlyPeriod * HOUR;
            }
        }
    }

    /**
     * Schedules a job to run once per day at the specified hour.
     *
     * <p> Note that the job will be scheduled to run at some arbitrary minute after the our based
     * on the hashcode of the name of the job identifier.
     *
     * <p> Your job will be run on a separate non-daemon thread, therefore the behavior on shutdown
     * must be taken into consideration for very long-running jobs.
     *
     * @param hour the hour of the day at which to execute this job.
     * @param ident a unique identifier that differentiates this job from all others scheduled on
     * the same database.
     * @param job a runnable that will be executed periodically <em>on a separate thread</em>.
     */
    public void scheduleAt (int hour, String ident, Runnable job)
    {
        int minOfHour = Math.abs(ident.toString().hashCode()) % HOUR;
        synchronized (_jobs) {
            _jobs.put(hour * HOUR + minOfHour, new Job(ident, job));
        }
    }

    /**
     * Removes the specified job from the schedule.
     */
    public void unschedule (String ident)
    {
        synchronized (_jobs) {
            // we have to iterate over our entire jobs table to remove all occurrances of this job,
            // but the table's not huge and we don't do this very often so it's no big deal
            for (Iterator<Map.Entry<Integer, Job>> iter = _jobs.entries().iterator();
                 iter.hasNext(); ) {
                Map.Entry<Integer, Job> entry = iter.next();
                if (entry.getValue().ident.equals(ident)) {
                    iter.remove();
                }
            }
        }
    }

    protected void executeJobs (int minuteOfDay)
    {
        int dayOfYear = Calendars.now().get(Calendar.DAY_OF_YEAR);
        List<Job> jobs = Lists.newArrayList();
        synchronized (_jobs) {
            List<Job> sched = _jobs.get(minuteOfDay);
            if (sched != null) {
                jobs.addAll(sched);
            }
        }
        for (Job job : jobs) {
            executeJob(job, dayOfYear, minuteOfDay);
        }
    }

    protected void executeJob (final Job job, int dayOfYear, int minuteOfDay)
    {
        if (!_cronRepo.claimJob(job.ident, dayOfYear, minuteOfDay)) {
            return; // another server is handling this execution
        }

        if (_running.putIfAbsent(job.ident, true) != null) {
            log.info("Dropping job as it is still executing", "job", job);
            return;
        }

        new Thread() {
            @Override public void run () {
                try {
                    job.job.run();
                } catch (Throwable t) {
                    log.warning("Job failed", "job", job, t);
                } finally {
                    _running.remove(job.ident);
                }
            }
        }.start();
    }

    protected static class Job
    {
        public final String ident;
        public final Runnable job;

        public Job (String ident, Runnable job) {
            this.ident = ident;
            this.job = job;
        }

        @Override public String toString () {
            return ident + " " + job;
        }
    }

    protected class JobTicker extends Interval
    {
        public JobTicker (RunQueue runQueue) {
            super(runQueue);
        }

        @Override public void expired () {
            // if the current minute is less than our previous, we wrapped around midnight
            int curMinute = getMinuteOfDay();
            if (curMinute < _prevMinute) {
                processMinutes(_prevMinute+1, 24*HOUR-1);
                processMinutes(0, curMinute);
            } else {
                processMinutes(_prevMinute+1, curMinute);
            }

            // note our previously executed minute
            _prevMinute = curMinute;

            // schedule ourselves for 60000 +/- rand(1000) millis in the future to randomize which
            // node is likely to win the job lottery every minute
            schedule(61*1000L - RandomUtil.getInt(2000));
        }

        protected int getMinuteOfDay () {
            _cal.setTimeInMillis(System.currentTimeMillis());
            return _cal.get(Calendar.HOUR_OF_DAY) * HOUR + _cal.get(Calendar.MINUTE);
        }

        protected void processMinutes (int fromMinute, int toMinute) {
            for (int mm = fromMinute; mm <= toMinute; mm++) {
                executeJobs(mm);
            }
        }

        protected Calendar _cal = Calendar.getInstance();
        protected int _prevMinute = getMinuteOfDay();
    }

    /** The ticker that handles our periodic jobs. */
    protected JobTicker _ticker;

    /** A map of all jobs scheduled for a single day. */
    protected ListMultimap<Integer, Job> _jobs = ArrayListMultimap.create();

    /** A map of jobs currently running on this node. */
    protected ConcurrentMap<String, Boolean> _running = new ConcurrentHashMap<String, Boolean>();

    // los dependidos
    @Inject protected CronRepository _cronRepo;

    protected static final int HOUR = 60; // in minutes
}
