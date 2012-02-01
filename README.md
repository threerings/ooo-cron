ooo-cron provides a simple multiserver-coordinate cron service. Coordination
takes place via a shared database table, which is used to ensure that only one
of the various servers executes a cron job for a given occurrence.

Jobs can be scheduled to run on a given period, or at a specific time of day.
See the `CronLogic` class for details.

Dependencies
------------

[Guice] is used to inject dependencies, and [Depot] is used for database
access.

Distribution
------------

ooo-cron is released under the New BSD License. The most recent version of the
library is available at http://github.com/threerings/ooo-cron

Contact
-------

Questions, comments, and other communications should be directed to the
[Three Rings Libraries] Google Group.

[Guice]: http://code.google.com/p/google-guice/
[Depot]: http://code.google.com/p/depot/
[Three Rings Libraries]: http://groups.google.com/group/ooo-libs
