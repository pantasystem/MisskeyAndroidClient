@file:Suppress("DEPRECATION")

package net.pantasystem.milktea.notification

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.lifecycle.lifecycleScope
import com.wada811.databinding.dataBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import net.pantasystem.milktea.app_store.account.AccountStore
import net.pantasystem.milktea.common.ui.ToolbarSetter
import net.pantasystem.milktea.common_android_ui.PageableFragmentFactory
import net.pantasystem.milktea.common_android_ui.account.page.PageTypeHelper
import net.pantasystem.milktea.common_android_ui.user.FollowRequestsFragmentFactory
import net.pantasystem.milktea.model.account.page.PageType
import net.pantasystem.milktea.model.account.page.Pageable
import net.pantasystem.milktea.model.account.page.PageableTemplate
import net.pantasystem.milktea.notification.databinding.FragmentNotificationMentionBinding
import javax.inject.Inject

@FlowPreview
@ExperimentalCoroutinesApi
@AndroidEntryPoint
class NotificationMentionFragment : Fragment(R.layout.fragment_notification_mention) {

    private val mBinding: FragmentNotificationMentionBinding by dataBinding()
    @Inject
    lateinit var accountStore: AccountStore

    @Inject
    lateinit var pageableFragmentFactory: PageableFragmentFactory

    @Inject
    lateinit var followRequestsFragmentFactory: FollowRequestsFragmentFactory

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)


        val pagerItems = listOf(
            TabType.TitleWithPageable(
                PageTypeHelper.nameByPageType(requireContext(), PageType.NOTIFICATION),
                PageableTemplate(null)
                    .notification(
                        PageTypeHelper.nameByPageType(
                            requireContext(),
                            PageType.NOTIFICATION
                        )
                    ).pageable(),
            ),
            TabType.TitleWithPageable(
                PageTypeHelper.nameByPageType(requireContext(), PageType.MENTION),
                PageableTemplate(null)
                    .mention(PageTypeHelper.nameByPageType(requireContext(), PageType.MENTION))
                    .pageable(),
            ),
            TabType.FollowRequests(
                getString(R.string.notifications_follow_requests)
            )
        )

        val notificationPagerAdapter = PagerAdapter(pagerItems)

        mBinding.notificationPager.adapter = notificationPagerAdapter
        mBinding.notificationTab.setupWithViewPager(mBinding.notificationPager)


        accountStore.observeCurrentAccount.filterNotNull().onEach {
            notificationPagerAdapter.notifyDataSetChanged()
        }.launchIn(lifecycleScope)

    }

    override fun onResume() {
        super.onResume()

        (requireActivity() as? ToolbarSetter?)?.apply {
            setToolbar(mBinding.toolbar)
            setTitle(R.string.notification)
        }

    }
    inner class PagerAdapter(val pages: List<TabType>) :
        FragmentStatePagerAdapter(childFragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

        private fun createFragment(position: Int): Fragment {
            return when(val tabItem = pages[position]) {
                is TabType.FollowRequests -> {
                    followRequestsFragmentFactory.create()
                }
                is TabType.TitleWithPageable -> {
                    pageableFragmentFactory.create(tabItem.pageable)
                }
            }
        }

        override fun getPageTitle(position: Int): CharSequence {
            return pages[position].title
        }

        override fun getItem(position: Int): Fragment {
            return createFragment(position)
        }

        override fun getCount(): Int {
            return pages.size
        }


    }
}



sealed interface TabType {
    val title: String
    data class TitleWithPageable(
        override val title: String,
        val pageable: Pageable,
    ) : TabType

    data class FollowRequests(
        override val title: String,
    ) : TabType
}