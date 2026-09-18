# Research

## Official Fastrack companion surface

Current Fastrack Smart World documentation on Google Play describes:
- watch connection/disconnection
- firmware/software updates
- watch settings
- health data and health settings including HR, SpO2 and BP
- notification access
- fitness, multi-sport and sleep sync
- favorite contacts
- Google Fit
- call, SMS and third-party notifications
- weather

Source: https://play.google.com/store/apps/details?id=com.titan.fastrack.reflex

## Related Titan/Fastrack companion research

Titan Smart World currently describes health trends, smartwatch settings/watch faces/notifications, fitness and sports, Health Connect, themes and dashboard customization.

Source: https://play.google.com/store/apps/details?id=com.titan.smartworld

A related Fastrack Reflex Vox listing describes daily activity, HR/SpO2, sleep, notifications, female-health tracking, phone finder, music/camera control, reminders and weather. This is sibling-product evidence only and must not be promoted to FT_38093 semantics without direct captures.

Source: https://play.google.com/store/apps/details?id=com.fastrack.reflex.vox

## Play Protect distribution

Google Play Protect currently states that internet-sideloaded apps declaring sensitive permissions including NOTIFICATION_LISTENER may be automatically blocked in affected markets. Google also lists health/fitness apps relaying notifications to wearable hardware as an allowed notification-listener use case when used as intended and with user consent.

Source: https://developers.google.com/android/play-protect/warning-dev-guidance

## Engineering conclusion

Public feature lists define the target product surface. Complete FT_38093 implementation requires separate evidence for every watch-side command/response and every semantic field. The app therefore uses explicit feature states rather than pretending related-watch protocol research is model validation.
