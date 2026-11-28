import type { ReactNode } from 'react';
import { Dialog as BaseDialog } from '@base-ui/react/dialog';
export function Dialog({ open, onClose, title, description, busy = false, children, className = '' }: {
  open: boolean; onClose: () => void; title: string; description?: string; busy?: boolean; children: ReactNode; className?: string;
}) {
  return <BaseDialog.Root open={open} onOpenChange={value => { if (!value && !busy) onClose(); }}>
    <BaseDialog.Portal><BaseDialog.Backdrop className="app-dialog-backdrop" /><BaseDialog.Popup className={'app-dialog app-surface ' + className}>
      <BaseDialog.Title>{title}</BaseDialog.Title>{description && <BaseDialog.Description>{description}</BaseDialog.Description>}
      {children}
    </BaseDialog.Popup></BaseDialog.Portal>
  </BaseDialog.Root>;
}
