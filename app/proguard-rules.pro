# Keep rules for the release build.
#
# Everything here is for code that is reached by name at runtime rather than by a
# reference R8 can see. Nothing else is listed: a keep rule for something R8 would
# have kept anyway is a rule nobody can ever safely remove, because there is no
# way to tell it apart from one that is load-bearing.

# The scheduled backup. WorkManager builds this from a class name string it stored
# in its own database, and calls the two-argument constructor reflectively. The
# class itself is referenced in code -- `PeriodicWorkRequestBuilder<...>` -- so R8
# keeps it; the constructor is not, and losing it would break the schedule at the
# moment it fires, which is unattended and silent. androidx.work ships a rule of
# its own that covers this, and it is repeated here because a backup that stops
# running is not a failure anybody would notice for a fortnight.
-keep public class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# AppAuth parses its own state out of JSON with reflection over field names, and
# stores that state across process death. A renamed field reads back as a missing
# one, which would present as being silently signed out.
-keep class net.openid.appauth.** { *; }

# The line numbers in a crash report, which are worth more than the few kilobytes
# the table costs.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
