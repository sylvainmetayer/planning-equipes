---
twin: installer/rgpd-sous-traitance
---

# Sign the processing agreement with your client

Before the first production release, even for free hosting, you must have signed with your client a written agreement whose content is set by article 28 of the GDPR; for a solo integrator and an association, it fits on two pages. This page gives the six points to put in it, what each contains for this application and what not to sign. It is not legal advice: the exact classification of the data controller in an association and the scope of the assistance commitment deserve a lawyer's look.

The deadline is the first production release, even for free
hosting. Article 28 does not require a commercial contract: it requires a written document
signed by both parties, with mandatory content.

For now, a standalone document serving as a processing agreement
is enough. The day general terms of sale exist, it becomes
their annex without being rewritten.

## The six points required by art. 28.3

| Point | What it contains here |
| --- | --- |
| Subject, duration, nature and purpose | Hosting and operation of a team scheduling tool, for the duration of the service |
| Categories of data and of people | See the baseline of the [entry template](rgpd.md#remplir-une-entrée-du-registre), including **minors**: that word justifies the reinforced measures |
| Processor's obligations | Act only on documented instructions, confidentiality of authorised persons, security measures (see [the entry template](rgpd.md#remplir-une-entrée-du-registre)), **no sub-processing without authorisation**; the building blocks of [the list of sub-processors](rgpd.md#qui-est-quoi) are therefore named in the document |
| Fate of the data at the end of the contract | Return or deletion, **at the controller's choice**, to be settled explicitly. Return relies on the SQL export ([`import-export.md`](../comprendre/exports-et-archives.md)), deletion on the purge procedure (see the purge procedure in [`exploitation.md`](exploitation.md)) |
| Assistance | Help in answering requests to exercise rights, and **breach notification without delay**; the procedure and the committed deadline are in [the breach procedure](rgpd-atteinte-aux-donnees.md), from which the clause can be copied |
| Audit | Making available the information needed to demonstrate compliance: the instance's register and the description of its hardening ([Exposing the instance on the Internet](securite.md)) |

## What not to sign

Do not copy a generic DPA template. The ones in circulation are written for
multi-client SaaS providers and promise what a solo integrator
cannot deliver: on-call duty, on-site audit, certified sub-processors. Signing an
obligation you will not keep is worse than signing nothing, because it is an
enforceable commitment. The CNIL (the French data protection authority) template for small organisations is the
right starting point; the table above says what to put in it.
