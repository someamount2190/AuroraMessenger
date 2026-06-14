package com.aura.legal

/**
 * In-app legal documents (Terms & Conditions, Privacy Policy), authored by the
 * app owner. Stored verbatim as lightweight markdown so they render natively and
 * theme with the rest of the app (including dark mode), so no WebView is needed.
 *
 * Markdown-lite grammar understood by [com.aura.ui.legal.LegalScreen]:
 *   "# "  section heading        "## " sub-heading
 *   "- "  bullet item            "! "  emphasised callout
 *   blank line separates paragraphs; any other line is a paragraph.
 */
data class LegalDoc(
    val key: String,
    val title: String,
    val effectiveDate: String,
    val body: String
)

object LegalDocs {
    const val CONTACT_EMAIL = "christiancorrea26@gmail.com"
    const val DEVELOPER = "Christian Lim Correa"
    const val EFFECTIVE_DATE = "June 14, 2026"

    val TERMS = LegalDoc(
        key = "terms",
        title = "Terms & Conditions",
        effectiveDate = EFFECTIVE_DATE,
        body = """
# 1. Acceptance of Terms
By downloading, installing, or using Aurora Messenger, you agree to be bound by these Terms and Conditions in their entirety. If you do not agree with any part of these Terms, you must not use Aurora Messenger. These Terms apply to all users of the Aurora Messenger application worldwide.

# 2. About Aurora Messenger
Aurora Messenger is a private messaging application developed by Christian Lim Correa. Aurora Messenger enables direct encrypted communication between devices without routing messages through central servers. Aurora Messenger is completely free to use. There are no subscription fees, no in-app purchases, and no advertising of any kind. Aurora Messenger is provided solely as a tool for private, lawful personal communication.

# 3. Eligibility and Age Requirements
## 3.1 Minimum Age
You must be at least 13 years of age to use Aurora Messenger. By installing and using Aurora Messenger, you confirm that you meet this minimum age requirement.
## 3.2 Minors Between 13 and 17
Users between the ages of 13 and 17 may only use Aurora Messenger with the knowledge, supervision, and explicit consent of a parent or legal guardian. The parent or guardian accepts these Terms on behalf of the minor and assumes full responsibility for the minor's use of the application.
## 3.3 Parental Controls
Aurora Messenger's QR pairing system gives parents direct control over who their child may communicate with. A child's device can only establish communication with contacts whose QR code has been explicitly scanned and approved. Parents are strongly encouraged to manage their child's Aurora Messenger contacts personally.

# 4. Permitted Use
Aurora Messenger may be used for any lawful personal communication, including the following:
- Private text messaging between individuals
- Sharing personal photos, videos, and media files
- Voice and video calls
- Family and personal correspondence

# 5. Prohibited Use
You may not use Aurora Messenger for any of the following purposes:
- Any activity that is illegal under applicable local, national, or international law
- Harassment, threats, abuse, stalking, or intimidation of any individual
- Distribution of child sexual abuse material or any content that exploits, sexualizes, or harms minors in any way
- Grooming, solicitation, or any form of inappropriate communication directed at minors
- Distribution of malware, spyware, ransomware, viruses, or any malicious software
- Any attempt to compromise, disrupt, or attack the security or integrity of the Aurora Messenger network or its users
- Impersonation of any person, entity, or organization
- Non-consensual sharing of intimate or private images of another person
! Zero Tolerance for Child Exploitation: Any use of Aurora Messenger to exploit, harm, groom, or produce illegal content involving minors is strictly and absolutely prohibited. We will cooperate fully with law enforcement authorities in any related investigation to the fullest extent technically possible.

# 6. Free Service
Aurora Messenger is and will remain completely free to use. There are no subscription fees, no in-app purchases, no premium tiers, and no advertising. Every feature of Aurora Messenger is available to all users at no cost, without limitation.

# 7. No Account Required
Aurora Messenger does not require you to create an account, provide a phone number, or submit any personal information. Your Aurora Messenger identity consists of a cryptographic key pair generated entirely on your own device. You are solely responsible for maintaining access to your device and safeguarding your Aurora Messenger identity.

# 8. Data and Privacy
The collection and use of data within Aurora Messenger is governed by our Privacy Policy, which is incorporated into these Terms by reference. In summary, Aurora Messenger stores no messages, no media, and no personal identity information. Our server retains only anonymous, automatically expiring technical data that is strictly necessary to connect two devices.

# 9. ShadowMesh Network
Aurora Messenger offers an optional opt-in feature called ShadowMesh, which allows your device to participate in a decentralized relay network. By choosing to opt in, you agree to the following:
- Your device will relay encrypted message fragments on behalf of other Aurora Messenger users
- You will not have access to the content of any relayed messages, as all content is encrypted end-to-end
- You may opt out of ShadowMesh at any time through the Settings menu
- Participation in ShadowMesh is entirely voluntary and has no effect on Aurora Messenger's core messaging functionality

# 10. Disclaimer of Warranties
Aurora Messenger is provided on an as-is and as-available basis without warranty of any kind, whether express or implied. We do not warrant that Aurora Messenger will be uninterrupted, error-free, or entirely secure at all times. While we have taken extensive technical measures to protect user privacy, no system can guarantee complete immunity from all possible threats.

# 11. Limitation of Liability
To the maximum extent permitted by applicable law, Christian Lim Correa shall not be liable for any indirect, incidental, special, exemplary, or consequential damages arising from or related to your use of Aurora Messenger or your inability to use Aurora Messenger.

# 12. Open Source & Intellectual Property
Aurora Messenger's source code is free and open source, licensed under the GNU Affero General Public License, version 3 (AGPL-3.0). You are free to use, study, modify, and redistribute it under the terms of that license (including running your own copy of the rendezvous server), provided you comply with it. Among other things, the AGPL requires that if you run a modified version as a network service, you make your modified source available to its users. Nothing in these Terms limits the rights the AGPL grants you.

The name "Aurora", the Aurora branding, logo, and visual identity are not covered by that license and remain the property of Christian Lim Correa. Please do not use them in a way that implies official endorsement, or that presents a modified version as the official Aurora.

# 13. Termination
We reserve the right to restrict or terminate access to Aurora Messenger for any user who violates these Terms, with particular regard to the prohibited use provisions outlined in Section 5. Because Aurora Messenger operates without central user accounts, termination may take the form of blocking a Node ID from accessing the rendezvous server.

# 14. Changes to Terms
We may update these Terms and Conditions from time to time. We will notify you of any significant changes through the application. Continued use of Aurora Messenger following the posting of revised Terms constitutes your acceptance of those changes.

# 15. Governing Law
These Terms shall be governed by and construed in accordance with applicable law. Any disputes arising out of or in connection with these Terms or your use of Aurora Messenger shall be subject to the exclusive jurisdiction of the competent courts of the applicable governing jurisdiction.

# 16. Contact
For any questions or concerns regarding these Terms and Conditions, please contact us at: christiancorrea26@gmail.com
""".trim()
    )

    val PRIVACY = LegalDoc(
        key = "privacy",
        title = "Privacy Policy",
        effectiveDate = EFFECTIVE_DATE,
        body = """
# 1. Our Privacy Promise
Aurora Messenger was built on one principle: your private conversations belong only to you and the person you are talking to. Aurora Messenger is free, permanently. There are no subscriptions, no advertisements, and no data selling of any kind. We have designed Aurora Messenger so that we are technically incapable of reading your messages. This is not merely a promise but an architectural guarantee. This Privacy Policy explains precisely what we collect, what we do not collect, and why.

# 2. What We Do Not Collect
Aurora Messenger does not collect any of the following:
- Your name, phone number, or email address
- Your messages, photos, videos, or any media you send or receive
- Your contact list or any information about who you communicate with
- Your location data
- Behavioral data, analytics, or usage patterns
- Any data for advertising or commercial profiling purposes
- Any payment information, as Aurora Messenger is completely free

# 3. What We Do Collect
Aurora Messenger collects only the minimum data necessary to operate the service.
## 3.1 Anonymous Node ID
When you install Aurora Messenger, your device generates a unique random identifier called a Node ID. This identifier is generated entirely on your device and stored only on your device. It contains no personal information and cannot be traced back to you by anyone, including us.
## 3.2 IP Address (Temporary)
To help your phone locate the person you want to communicate with across the internet, Aurora Messenger's rendezvous server temporarily stores your Node ID alongside your current IP address. This record expires automatically after 15 minutes of inactivity. We do not log these records, we do not retain them beyond their expiry, and they exist solely for the purpose of helping two devices establish a direct connection. All such records are deleted automatically.
## 3.3 A Connection to Wake Your Device
So that you receive messages and calls even when the app is closed, your device keeps a lightweight connection open to the rendezvous server. This connection lets the server wake your device when someone is trying to reach you. While the connection is open, the server can tell that your device is reachable, but it learns nothing else: it never sees your messages, your media, or who you are. The wake signal it passes along carries no content of any kind. It is only an instruction for your device to come online, after which the actual message travels directly from the sender's device to yours. We keep no logs of these connections or wake signals.
## 3.4 Summary
The information we handle is limited to your anonymous Node ID, a temporary record of your IP address, and a transient connection that tells the server only that your device is reachable. We store no messages, no media, no personal identity, no history, and no payment data, and we keep no logs.

# 4. How Your Messages Are Protected
Every message you send is encrypted on your device before it leaves. It travels directly to the recipient's device and is decrypted only upon arrival. Aurora Messenger's server is never in the path of your messages, not even as an encrypted relay.
Aurora Messenger uses post-quantum cryptography, including Kyber-1024 and XChaCha20-Poly1305 encryption standards. This protects your conversations not only against present-day threats but also against future quantum computing capabilities. Your messages are protected for the long term.

# 5. Children's Privacy
Aurora Messenger is not intended for children under the age of 13. We do not knowingly collect any data from children under 13. Users between the ages of 13 and 17 may use Aurora Messenger only with the knowledge and consent of a parent or legal guardian.
Aurora Messenger can serve as a safer communication tool for families. When a parent pairs Aurora Messenger with their child's device using the QR pairing system, the parent directly controls who the child may communicate with. There is no algorithm, no exposure to strangers, and no data harvesting of any kind.
If you believe a child under the age of 13 is using Aurora Messenger without parental consent, please contact us at christiancorrea26@gmail.com and we will take appropriate action promptly.

# 6. ShadowMesh Network (Optional Opt-In)
Aurora Messenger offers an optional feature called ShadowMesh. Users who choose to opt in allow their device to help relay encrypted message fragments for other Aurora Messenger users. You will never have access to the content of these messages, as they are encrypted end-to-end between the original sender and recipient. Your device functions as an anonymous relay node only.

# 7. Data Security
All messages are encrypted end-to-end using post-quantum cryptography. Encryption keys are generated and held only on your device, protected by Android's hardware-backed Keystore. Our server stores only anonymous, auto-expiring records. We maintain a strict zero-log policy on our rendezvous server.

# 8. Data Sharing
We do not sell your data. We do not share your data with advertisers. We do not share your data with any third party for any commercial or non-commercial purpose. We have no data to share because we do not collect it.
In the event of a legal request or court order, we are only able to provide what exists in our system: anonymous Node IDs and temporarily cached IP addresses that expire automatically. We have no message content, no personal identities, and no communication history to provide to any party.

# 9. Your Rights
Because Aurora Messenger stores no personal data linked to your identity, most traditional data subject rights do not apply in the conventional sense. However, the following applies to all users:
- You may delete all Aurora Messenger data at any time using the Clear All Data function in Settings
- Deleting the application removes all locally stored keys, contacts, and messages permanently
- There is no account to delete because no account is created

# 10. Changes to This Policy
We may update this Privacy Policy from time to time. We will notify you of any significant changes through the application. Continued use of Aurora Messenger following such changes constitutes your acceptance of the updated policy.

# 11. Contact
For privacy-related questions or concerns, please contact us at: christiancorrea26@gmail.com
""".trim()
    )

    fun byKey(key: String): LegalDoc = if (key == PRIVACY.key) PRIVACY else TERMS
}
