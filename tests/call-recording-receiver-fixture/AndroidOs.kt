// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package android.os
class UserManager(val isUserUnlocked: Boolean)
object Build {
    object VERSION { var SDK_INT = 35 }
    object VERSION_CODES { const val S = 31 }
}
