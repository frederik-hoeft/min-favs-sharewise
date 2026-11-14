import { Badge } from "@mantine/core";
import { ParticipantAvatar } from "@/components/participant-avatar/participant-avatar";
import { preview, euro } from "../_data/preview";
import styles from "./feature-stories.module.scss";

export function FeatureStories() {
  return (
    <div className={styles.grid}>
      <article className={styles.story}>
        <div className={styles.visual}>
          <div className={styles.groupCard}>
            <div className={styles.groupSymbol} aria-hidden="true">⌂</div>
            <div className={styles.groupText}>
              <strong>{preview.name}</strong>
              <span>{preview.participants.length} members · Shared space</span>
            </div>
            <div className={styles.groupAvatars} role="img" aria-label="Four group members">
              {preview.participants.map((person) => (
                <ParticipantAvatar size={32} key={person.name} name={person.name} initials={person.initials} tint={person.tint} />
              ))}
            </div>
          </div>
          <div className={styles.visualNote}>One group, everyone in the loop.</div>
        </div>
        <div className={styles.index}>01 / GET TOGETHER</div>
        <h3>Make room for everyone.</h3>
        <p>Start with a group and the people in it. A shared home for the costs that come with doing things together.</p>
      </article>
      <article className={styles.story}>
        <div className={styles.visual}>
          <div className={styles.splitCard}>
            <div className={styles.splitTop}><span>{preview.allocationExample.title}</span><strong>{euro(preview.allocationExample.amount)}</strong></div>
            <span className={styles.splitLabel}>{preview.allocationExample.label.toUpperCase()} · {preview.allocationExample.members} PEOPLE</span>
            <div className={styles.splitBar} role="img" aria-label={`${preview.allocationExample.members} equal shares of ${euro(preview.allocationExample.share)} each`}><i /><i /><i /><i /></div>
            <div className={styles.splitBottom}><span>{euro(preview.allocationExample.share)} each</span><Badge color="forest" variant="light" size="sm">Allocated</Badge></div>
          </div>
          <div className={styles.visualNote}>No mystery math.</div>
        </div>
        <div className={styles.index}>02 / SHARE THE COST</div>
        <h3>Every expense has its place.</h3>
        <p>Know who covered the bill and how the cost was assigned. Equal, fixed or percentage splits, clearly documented.</p>
      </article>
      <article className={styles.story}>
        <div className={styles.visual}>
          <div className={styles.transferCard}>
            <span className={styles.transferLabel}>RECORDED SETTLEMENT</span>
            <div className={styles.transferPeople}>
              <span className={styles.person}><ParticipantAvatar size={39} name="Noah" initials="NO" tint="blue" /><strong>Noah</strong></span>
              <span className={styles.transferArrow}>→</span>
              <span className={styles.person}><ParticipantAvatar size={39} name="Maya" initials="MA" tint="green" /><strong>Maya</strong></span>
            </div>
            <strong className={styles.transferAmount}>{euro(preview.settlementTotal)}</strong>
            <span className={styles.transferFoot}>Transfer happened outside ShareWise</span>
          </div>
        </div>
        <div className={styles.index}>03 / KEEP IT STRAIGHT</div>
        <h3>Close the loop, your way.</h3>
        <p>Record money sent between members outside the app, and see it reflected in the group&apos;s balances. No payment processing.</p>
      </article>
    </div>
  );
}
