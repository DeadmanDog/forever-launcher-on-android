package app.gamenative.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Raw color primitives for the Pluvia app.
 * These are the base colors used to construct theme palettes.
 */

// Brand
val PluviaPrimary: Color get() = WowTheme.palette.accent

// Backgrounds
val PluviaBackground: Color get() = WowTheme.palette.bgTop
val PluviaSurface: Color get() = WowTheme.palette.card
val PluviaSurfaceElevated: Color get() = WowTheme.palette.elevated
val PluviaCard: Color get() = WowTheme.palette.card

// Foregrounds
val PluviaForeground: Color get() = WowTheme.palette.text
val PluviaForegroundMuted: Color get() = WowTheme.palette.muted

// Secondary
val PluviaSecondary: Color get() = WowTheme.palette.elevated

// Accents
val PluviaCyan: Color get() = WowTheme.palette.accent
val PluviaPurple: Color get() = WowTheme.palette.accent
val PluviaPink: Color get() = WowTheme.palette.accentFill

// Semantic
val PluviaSuccess = Color(0xFF10B981)
val PluviaWarning = Color(0xFFF59E0B)
val PluviaDanger = Color(0xFFEF4444)
val PluviaDestructive = Color(0xFF7F1D1D)

// Border
val PluviaBorder: Color get() = WowTheme.palette.cardBorder

// Status - Installed/Download states
val StatusInstalled = Color(0xFF4CAF50)
val StatusDownloading = Color(0xFF00BCD4)
val StatusAvailable = Color(0xFF2196F3)
val StatusAway = Color(0xFFFF9800)
val StatusOffline = Color(0xFF9E9E9E)

// Friend states
val FriendOnline = Color(0xFF6DCFF6)
val FriendOffline = Color(0xFF7A7A7A)
val FriendInGame = Color(0xFF90BA3C)
val FriendAwayOrSnooze = Color(0x806DCFF6)
val FriendInGameAwayOrSnooze = Color(0x8090BA3C)
val FriendBlocked = Color(0xFF983D3D)

// Compatibility
val CompatibilityGood = Color(0xFF4CAF50)
val CompatibilityGoodBg = Color(0xFF1B5E20)
val CompatibilityPartial = Color(0xFF8BC34A)
val CompatibilityPartialBg = Color(0xFF33691E)
val CompatibilityUnknown = Color(0xFF9E9E9E)
val CompatibilityUnknownBg = Color(0xFF424242)
val CompatibilityBad = Color(0xFFEF5350)
val CompatibilityBadBg = Color(0xFFB71C1C)
