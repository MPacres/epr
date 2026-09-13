import type { ButtonHTMLAttributes } from 'react'
import { cva, type VariantProps } from 'class-variance-authority'
import { clsx } from 'clsx'
import { twMerge } from 'tailwind-merge'

const buttonVariants = cva('inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-lg text-sm font-medium transition-colors disabled:pointer-events-none disabled:opacity-45', {
  variants: {
    variant: {
      default: 'bg-primary text-white hover:bg-primary-hover',
      outline: 'border border-border bg-white text-foreground hover:bg-muted',
      ghost: 'text-primary hover:bg-accent',
    },
    size: { default: 'min-h-10 px-4 py-2', sm: 'min-h-9 px-3 py-1.5', icon: 'size-11 shrink-0' },
  },
  defaultVariants: { variant: 'default', size: 'default' },
})
export function Button({ className, variant, size, type = 'button', ...props }: ButtonHTMLAttributes<HTMLButtonElement> & VariantProps<typeof buttonVariants>) {
  return <button type={type} className={twMerge(clsx(buttonVariants({ variant, size }), className))} {...props} />
}
