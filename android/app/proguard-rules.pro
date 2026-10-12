#
# Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
#
# proguard-rules.pro — R8/ProGuard keep rules for the Decoder application
#

# Keep the Frame record used across threads via BlockingQueue
-keep class com.openipc.decoder.Decoder$Frame { *; }
