package jp.panta.misskeyandroidclient.model.account


data class AccountState(
    val currentAccountId: Long? = null,
    val accounts: List<Account> = emptyList(),
    val isLoading: Boolean = true,
    val error: Throwable? = null
) {

    val currentAccount: Account?
        get() = accounts.firstOrNull { it.accountId == currentAccountId }
            ?: accounts.firstOrNull()

    val isUnauthorized: Boolean
        get() = accounts.isEmpty() && !isLoading

    fun hasAccount(account: Account): Boolean {
        return accounts.any { it.accountId == account.accountId }
    }

    fun add(account: Account): AccountState {
        return copy(
            accounts = if (hasAccount(account)) {
                accounts.map {
                    if (it.accountId == account.accountId) {
                        account
                    } else {
                        it
                    }
                }
            } else {
                accounts.toMutableList().also { list ->
                    list.add(account)
                }
            }
        )
    }

    fun delete(accountId: Long): AccountState {
        val filtered = accounts.filter { it.accountId == accountId }
        return copy(
            currentAccountId = if (currentAccountId == accountId) {
                filtered.firstOrNull()?.accountId
            } else {
                currentAccountId
            },
            accounts = filtered
        )
    }

    fun setCurrentAccount(account: Account): AccountState {
        return add(account).copy(currentAccountId = account.accountId)
    }
}
