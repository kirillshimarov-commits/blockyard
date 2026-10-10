import { Instance } from "../api";

/** Modpack icon if there is one, otherwise the first letter on an accent gradient. */
export default function InstanceCover({ instance, large = false }: { instance: Instance; large?: boolean }) {
  return (
    <div className={`instance-cover ${large ? "large" : ""} ${instance.iconUrl ? "with-icon" : ""}`}>
      {instance.iconUrl ? <img src={instance.iconUrl} alt="" /> : <span>{instance.name.slice(0, 1).toUpperCase()}</span>}
    </div>
  );
}
