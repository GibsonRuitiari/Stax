package com.hover.stax.transfers

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.hover.sdk.actions.HoverAction
import com.hover.stax.R
import com.hover.stax.accounts.Account
import com.hover.stax.actions.ActionSelect
import com.hover.stax.actions.ActionSelectViewModel
import com.hover.stax.bonus.Bonus
import com.hover.stax.bonus.BonusViewModel
import com.hover.stax.contacts.ContactInput
import com.hover.stax.contacts.StaxContact
import com.hover.stax.databinding.FragmentTransferBinding
import com.hover.stax.home.MainActivity
import com.hover.stax.utils.AnalyticsUtil
import com.hover.stax.utils.UIHelper
import com.hover.stax.utils.Utils
import com.hover.stax.views.AbstractStatefulInput
import com.hover.stax.views.Stax2LineItem
import com.hover.stax.views.StaxTextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.getSharedViewModel
import org.koin.androidx.viewmodel.ext.android.sharedViewModel
import timber.log.Timber


class TransferFragment : AbstractFormFragment(), ActionSelect.HighlightListener, NonStandardVariableAdapter.NonStandardVariableInputListener {

    private val actionSelectViewModel: ActionSelectViewModel by sharedViewModel()
    private val bonusViewModel: BonusViewModel by sharedViewModel()
    private lateinit var transferViewModel: TransferViewModel

    private val args by navArgs<TransferFragmentArgs>()

    private lateinit var amountInput: StaxTextInputLayout
    private lateinit var recipientInstitutionSelect: ActionSelect
    private lateinit var contactInput: ContactInput
    private lateinit var recipientValue: Stax2LineItem

    private var _binding: FragmentTransferBinding? = null
    private val binding get() = _binding!!

    private var nonStandardVariableAdapter: NonStandardVariableAdapter? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        abstractFormViewModel = getSharedViewModel<TransferViewModel>()
        transferViewModel = abstractFormViewModel as TransferViewModel

        setTransactionType(args.transactionType)

        args.transactionUUID?.let {
            transferViewModel.autoFill(it)
        }

        _binding = FragmentTransferBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onPause() {
        super.onPause()
        transferViewModel.setEditing(true)
    }

    override fun onResume() {
        super.onResume()

        amountInput.setHint(getString(R.string.transfer_amount_label))
        accountDropdown.setHint(getString(R.string.account_label))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        transferViewModel.reset()
        init(binding.root)

        startObservers(binding.root)
        startListeners()
    }

    override fun init(root: View) {
        setTitle()

        amountInput = binding.editCard.amountInput
        contactInput = binding.editCard.contactSelect
        recipientInstitutionSelect = binding.editCard.actionSelect
        recipientValue = binding.summaryCard.recipientValue

        if (actionSelectViewModel.filteredActions.value != null)
            recipientInstitutionSelect.updateActions(actionSelectViewModel.filteredActions.value!!)

        super.init(root)

        accountDropdown.setFetchAccountListener(this)

        amountInput.apply {
            setText(transferViewModel.amount.value)
            requestFocus()
        }
    }

    private fun setTitle() {
        val titleRes = if (TransactionType.type == HoverAction.AIRTIME) R.string.cta_airtime else R.string.cta_transfer
        binding.editCard.transferCard.setTitle(getString(titleRes))
        binding.summaryCard.transferSummaryCard.setTitle(getString(titleRes))
    }

    private fun setTransactionType(txnType: String) {
        transferViewModel.setTransactionType(txnType)
        channelsViewModel.setType(txnType)
    }

    override fun startObservers(root: View) {
        super.startObservers(root)
        observeActionSelection()
        observeAccountList()
        observeActiveChannel()
        observeActions()
        observeAmount()
        observeNote()
        observeRecentContacts()
        observeNonStandardVariables()
        observeAutoFillToInstitution()

        with(transferViewModel) {
            contact.observe(viewLifecycleOwner) { recipientValue.setContact(it) }
            request.observe(viewLifecycleOwner) {
                AnalyticsUtil.logAnalyticsEvent(getString(R.string.loaded_request_link), requireContext())
                it?.let { view(it) }
            }
        }
    }

    private fun observeActionSelection() {
        actionSelectViewModel.activeAction.observe(viewLifecycleOwner) {
            recipientInstitutionSelect.selectRecipientNetwork(it)
            setRecipientHint(it)
        }
    }

    private fun observeActiveChannel() {
        channelsViewModel.activeChannel.observe(viewLifecycleOwner) { channel ->
            channel?.let {
                transferViewModel.request.value?.let { request ->
                    transferViewModel.setRecipientSmartly(request.requester_number, it)
                }
                binding.summaryCard.accountValue.setTitle(it.toString())
            }
            recipientInstitutionSelect.visibility = if (channel != null) View.VISIBLE else View.GONE

            checkForBonus()
        }
    }

    private fun observeActions() {
        channelsViewModel.channelActions.observe(viewLifecycleOwner) {
            actionSelectViewModel.setActions(it)
        }
        actionSelectViewModel.filteredActions.observe(viewLifecycleOwner) {
            recipientInstitutionSelect.updateActions(it)
        }
    }

    private fun observeAccountList() = with(channelsViewModel) {
        accounts.observe(viewLifecycleOwner) {
            if (it.isEmpty())
                setDropdownTouchListener(TransferFragmentDirections.actionNavigationTransferToAccountsFragment())

            if (args.channelId != 0) { //to be used with bonus flow. Other uses will require a slight change in this
                updateAccountDropdown()
                return@observe
            }

            if (args.transactionUUID == null) {
                accountDropdown.setCurrentAccount()
                return@observe
            }
        }

        if(args.transactionType == HoverAction.AIRTIME) {
            val observer = Observer<Account> { it?.let { setChannel(it) } }
            activeAccount.observe(viewLifecycleOwner, observer)
        }
    }

    private fun observeAmount() {
        transferViewModel.amount.observe(viewLifecycleOwner) {
            it?.let {
                binding.summaryCard.amountValue.text = Utils.formatAmount(it)
            }
        }
    }

    private fun observeAutoFillToInstitution() {
        transferViewModel.completeAutoFilling.observe(viewLifecycleOwner) {
            if (it != null && args.transactionUUID != null)
                completeAutoFilling(it)
        }
    }

    private fun observeNote() {
        transferViewModel.note.observe(viewLifecycleOwner) {
            binding.summaryCard.noteRow.visibility = if (it.isNullOrEmpty()) View.GONE else View.VISIBLE
            binding.summaryCard.noteValue.text = it
        }
    }

    private fun observeRecentContacts() {
        transferViewModel.recentContacts.observe(viewLifecycleOwner) {
            if (!it.isNullOrEmpty()) {
                contactInput.setRecent(it, requireActivity())
                transferViewModel.contact.value?.let { ct -> contactInput.setSelected(ct) }
            }
        }
    }

    private fun observeNonStandardVariables() {
        actionSelectViewModel.nonStandardVariables.observe(viewLifecycleOwner) { variables ->
            if (variables != null) {
                updateNonStandardForEntryList(variables)
                updateNonStandardForSummaryCard(variables)
            }
        }
    }

    private fun startListeners() {
        setAmountInputListener()
        setContactInputListener()

        recipientInstitutionSelect.setListener(this)
        fab.setOnClickListener { fabClicked() }

        binding.summaryCard.transferSummaryCard.setOnClickIcon { transferViewModel.setEditing(true) }
    }

    private fun setAmountInputListener() {
        amountInput.apply {
            addTextChangedListener(amountWatcher)
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus)
                    this.setState(
                        null,
                        if (transferViewModel.amountErrors() == null) AbstractStatefulInput.SUCCESS else AbstractStatefulInput.ERROR
                    )
                else
                    this.setState(null, AbstractStatefulInput.NONE)
            }
        }
    }

    private fun setContactInputListener() {
        contactInput.apply {
            setAutocompleteClickListener { view, _, position, _ ->
                val contact = view.getItemAtPosition(position) as StaxContact
                transferViewModel.setContact(contact)
            }
            addTextChangedListener(recipientWatcher)
            setChooseContactListener { contactPicker(requireActivity()) }
        }
    }

    private fun fabClicked() {
        if (validates()) {
            if (transferViewModel.isEditing.value == true) {
                transferViewModel.saveContact()
                transferViewModel.setEditing(false)
            } else {
                (requireActivity() as MainActivity).submit(
                    accountDropdown.highlightedAccount ?: channelsViewModel.activeAccount.value!!
                )
                findNavController().popBackStack()
            }
        } else UIHelper.flashMessage(requireActivity(), getString(R.string.toast_pleasefix))
    }

    private val amountWatcher: TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {}
        override fun afterTextChanged(editable: Editable) {}
        override fun onTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {
            transferViewModel.setAmount(charSequence.toString().replace(",".toRegex(), ""))
        }
    }

    private val recipientWatcher: TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {}
        override fun afterTextChanged(editable: Editable) {}
        override fun onTextChanged(charSequence: CharSequence, i: Int, i1: Int, afterCount: Int) {
            with(transferViewModel) {
                if (afterCount == 0) {
                    resetRecipient()
                    recipientValue.setContent("", "")
                } else
                    setRecipient(charSequence.toString())
            }
        }
    }

    private fun validates(): Boolean {
        val amountError = transferViewModel.amountErrors()
        amountInput.setState(amountError, if (amountError == null) AbstractStatefulInput.SUCCESS else AbstractStatefulInput.ERROR)

        val channelError = channelsViewModel.errorCheck()
        accountDropdown.setState(channelError, if (channelError == null) AbstractStatefulInput.SUCCESS else AbstractStatefulInput.ERROR)

        val actionError = actionSelectViewModel.errorCheck()
        recipientInstitutionSelect.setState(actionError, if (actionError == null) AbstractStatefulInput.SUCCESS else AbstractStatefulInput.ERROR)

        val recipientError = transferViewModel.recipientErrors(actionSelectViewModel.activeAction.value)
        contactInput.setState(recipientError, if (recipientError == null) AbstractStatefulInput.SUCCESS else AbstractStatefulInput.ERROR)

        val noNonStandardVarError = nonStandardVariableAdapter?.validates() ?: true

        channelsViewModel.activeAccount.value?.let {
            if (!channelsViewModel.isValidAccount()) {
                accountDropdown.setState(getString(R.string.incomplete_account_setup_header), AbstractStatefulInput.ERROR)
                fetchAccounts(it)
                return false
            }
        }

        return channelError == null && actionError == null && amountError == null && recipientError == null && noNonStandardVarError
    }

    override fun onContactSelected(contact: StaxContact) {
        transferViewModel.setContact(contact)
        contactInput.setSelected(contact)
    }

    override fun highlightAction(action: HoverAction?) {
        action?.let { actionSelectViewModel.setActiveAction(it) }
    }

    private fun updateNonStandardForEntryList(variables: LinkedHashMap<String, String>) {
        binding.editCard.nonStandardVariableRecyclerView.also {
            nonStandardVariableAdapter = NonStandardVariableAdapter(variables, this@TransferFragment, it)
            it.layoutManager = UIHelper.setMainLinearManagers(requireContext())
            it.adapter = nonStandardVariableAdapter
        }
    }

    private fun updateNonStandardForSummaryCard(variables: LinkedHashMap<String, String>) {
        binding.summaryCard.nonStandardSummaryRecycler.apply {
            layoutManager = UIHelper.setMainLinearManagers(requireContext())
            adapter = NonStandardSummaryAdapter(variables)
        }
    }

    private fun setRecipientHint(action: HoverAction) {
        binding.summaryCard.accountValue.setSubtitle(action.getNetworkSubtitle(requireContext()))
        editCard?.findViewById<LinearLayout>(R.id.recipient_entry)?.visibility = if (action.requiresRecipient()) View.VISIBLE else View.GONE
        binding.summaryCard.recipientRow.visibility = if (action.requiresRecipient()) View.VISIBLE else View.GONE

        if (!action.requiresRecipient()) {
            recipientValue.setContent(getString(R.string.self_choice), "")
        } else {
            transferViewModel.forceUpdateContactUI()
            contactInput.setHint(
                if (action.requiredParams.contains(HoverAction.ACCOUNT_KEY))
                    getString(R.string.recipientacct_label)
                else
                    getString(R.string.recipientphone_label)
            )
        }
    }

    private fun completeAutoFilling(data: AutofillData) {
        channelsViewModel.setChannelFromId(data.channelId, data.accountId)
        transferViewModel.contact.value?.let { contactInput.setText(it.shortName(), false) }
        amountInput.setText(transferViewModel.amount.value)
        transferViewModel.setEditing(data.isEditing)
        accountDropdown.setState(getString(R.string.channel_request_fieldinfo, data.institutionId.toString()), AbstractStatefulInput.INFO)
    }

    private fun checkForBonus() {
        if (args.transactionType == HoverAction.AIRTIME) {
            val bonuses = bonusViewModel.bonuses.value
            if (!bonuses.isNullOrEmpty())
                showBonusBanner(bonuses)
        }
    }

    private fun showBonusBanner(bonuses: List<Bonus>) = with(binding.bonusLayout) {
        val channelId = bonuses.first().userChannel

        cardBonus.visibility = View.VISIBLE
        val bonus = bonuses.first()
        val usingBonusChannel = channelsViewModel.activeChannel.value?.id == bonus.purchaseChannel

        learnMore.movementMethod = LinkMovementMethod.getInstance()

        if (usingBonusChannel) {
            title.text = getString(R.string.congratulations)
            message.text = getString(R.string.valid_account_bonus_msg)
            cta.visibility = View.GONE
        } else {
            title.text = getString(R.string.get_extra_airtime)
            message.text = getString(R.string.invalid_account_bonus_msg)
            cta.apply {
                visibility = View.VISIBLE
                text = getString(R.string.top_up_with_mpesa)
                setOnClickListener {
                    AnalyticsUtil.logAnalyticsEvent(getString(R.string.clicked_bonus_airtime_banner), requireActivity())
                    channelsViewModel.setActiveChannelAndAccount(bonus.purchaseChannel, channelId)
                }
            }
        }
    }

    override fun showEdit(isEditing: Boolean) {
        super.showEdit(isEditing)

        if (!isEditing)
            binding.bonusLayout.cardBonus.visibility = View.GONE
        else
            checkForBonus()
    }

    /**
     * Handles instances where the active account is different from the bonus account to be used.
     * ChannelId is fetched from the bonus object's user channel field.
     * Channel and respective accounts are fetched before being passed to account dropdown
     */
    private fun updateAccountDropdown() = lifecycleScope.launch(Dispatchers.IO) {
        val bonus = bonusViewModel.getBonusByPurchaseChannel(args.channelId)

        bonus?.let {
            val channel = channelsViewModel.getChannel(bonus.userChannel)
            channelsViewModel.setActiveChannelAndAccount(bonus.purchaseChannel, channel!!.id)
        } ?: run { Timber.e("Bonus cannot be found") }
    }

    /**
     * Monitors changes in active account from account dropdown and sets bonus channel
     */
    private fun setChannel(account: Account) = lifecycleScope.launch(Dispatchers.IO) {
        val bonus = bonusViewModel.getBonusByUserChannel(account.channelId)

        if (bonus != null) {
            val channel = channelsViewModel.getChannel(bonus.purchaseChannel)
            channelsViewModel.setActiveChannel(channel!!)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()

        if (dialog != null && dialog!!.isShowing) dialog!!.dismiss()
        _binding = null
    }

    override fun nonStandardVarUpdate(key: String, value: String) {
        actionSelectViewModel.updateNonStandardVariables(key, value)
    }
}