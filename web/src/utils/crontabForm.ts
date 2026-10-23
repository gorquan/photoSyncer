import { i18n } from '@/i18n'
import type { CrontabDto } from '@/__generated/model/dto'
import type { CrontabConfig, CrontabCreateInput } from '@/__generated/model/static'
import type { CrontabSyncMode } from '@/__generated/model/enums'

export type Crontab = CrontabDto['CrontabController/DEFAULT_CRONTAB']

// 生成的 DTO 字段均为 readonly，表单需要可写
type Writable<T> = { -readonly [P in keyof T]: T[P] }

export interface LocalCronForm extends Writable<Omit<CrontabCreateInput, 'albumIds'>> {
  albumIds: number[]
}

function trimTrailingSlash(path: string): string {
  return path.replace(/\/+$/, '')
}

/** The configured path is always a literal export root. */
export function targetRootPath(path: string): string {
  const trimmed = path.trim()
  if (!trimmed) return ''
  const expressionStart = trimmed.indexOf('${')
  return (
    trimTrailingSlash(expressionStart >= 0 ? trimmed.slice(0, expressionStart) : trimmed) || '/'
  )
}

export function targetBasePath(path: string): string {
  return targetRootPath(path)
}

export function displayTargetPath(path: string): string {
  return targetRootPath(path)
}

export function buildSubmitConfig(form: LocalCronForm): CrontabConfig {
  return {
    ...form.config,
    // 禁用任务不校验表达式，提交时兜底默认值避免空串
    expression: form.config.expression.trim() || '0 0 23 * * ?',
    targetPath: targetRootPath(form.config.targetPath),
  }
}

export function createDefaultCronConfig(defaultTz: string): CrontabConfig {
  return {
    expression: '0 0 23 * * ?',
    timeZone: defaultTz,
    targetPath: '/photos',
    downloadImages: true,
    downloadVideos: true,
    downloadLivePhotos: true,
    synologyPhotos: true,
    syncCloudDeletes: true,
    skipExistingFile: true,
    fetchFromDbSize: 2,
    downloaders: 8,
    verifiers: 2,
    exifProcessors: 2,
    notify: true,
  }
}

export function createEmptyCronForm(defaultTz: string, accountId: number): LocalCronForm {
  return {
    name: i18n.global.t('cronform.field.defaultName'),
    description: '',
    enabled: true,
    syncMode: 'TIMELINE' satisfies CrontabSyncMode,
    accountId,
    config: createDefaultCronConfig(defaultTz),
    albumIds: [],
  }
}

export function mapCrontabToForm(item: Crontab): LocalCronForm {
  return {
    name: item.name,
    description: item.description,
    enabled: item.enabled,
    syncMode: item.syncMode,
    accountId: item.accountId,
    config: {
      expression: item.config.expression,
      timeZone: item.config.timeZone,
      targetPath: displayTargetPath(item.config.targetPath),
      downloadImages: item.config.downloadImages,
      downloadVideos: item.config.downloadVideos,
      downloadLivePhotos: item.config.downloadLivePhotos ?? true,
      synologyPhotos: item.config.synologyPhotos ?? false,
      syncCloudDeletes: item.config.syncCloudDeletes ?? false,
      skipExistingFile: item.config.skipExistingFile ?? true,
      fetchFromDbSize: item.config.fetchFromDbSize ?? 2,
      downloaders: item.config.downloaders ?? 8,
      verifiers: item.config.verifiers ?? 2,
      exifProcessors: item.config.exifProcessors ?? 2,
      notify: item.config.notify ?? true,
    },
    albumIds: [...item.albumIds],
  }
}
