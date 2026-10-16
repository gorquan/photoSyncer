<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Column from 'primevue/column'
import DataTable from 'primevue/datatable'
import Dialog from 'primevue/dialog'
import Tag from 'primevue/tag'
import type { CrontabDto, CrontabHistoryDetailDto } from '@/__generated/model/dto'

type CrontabHistory = CrontabDto['CrontabController/DEFAULT_CRONTAB']['histories'][number]
type CrontabHistoryDetail =
  CrontabHistoryDetailDto['CrontabController/CRONTAB_HISTORY_DETAIL_FETCHER']

const props = withDefaults(
  defineProps<{
    visible: boolean
    history: CrontabHistory | null
    rows: ReadonlyArray<CrontabHistoryDetail>
    pageIndex: number
    pageSize: number
    totalRowCount: number
    loading?: boolean
    error?: string | null
  }>(),
  {
    loading: false,
    error: null,
  },
)

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'page', payload: { pageIndex: number; pageSize: number }): void
  (e: 'refresh'): void
}>()

const visibleProxy = computed({
  get: () => props.visible,
  set: (value: boolean) => emit('update:visible', value),
})

const maximized = ref(false)

const first = computed(() => props.pageIndex * props.pageSize)
const dialogStyle = computed(() =>
  maximized.value
    ? {
        width: '100vw',
        height: '100vh',
        maxHeight: '100vh',
      }
    : {
        width: '95vw',
        maxWidth: '72rem',
      },
)
const dialogPt = computed(() => ({
  root: {
    class: maximized.value ? '!m-0 flex h-screen max-h-screen flex-col' : 'flex flex-col',
  },
  content: {
    class: 'flex min-h-0 flex-1 flex-col overflow-hidden',
  },
}))

const { t, locale } = useI18n()

const historyStatus = computed(() => {
  const history = props.history
  if (!history) return { severity: 'secondary' as const, label: t('schedule.status.unknown') }
  if (
    history.executionStatus === 'FAILED' ||
    history.executionStatus === 'PARTIAL' ||
    history.executionStatus === 'CANCELLED'
  )
    return { severity: 'warn' as const, label: t(`schedule.status.${history.executionStatus}`) }
  if (history.isCompleted)
    return { severity: 'secondary' as const, label: t('schedule.status.finished') }
  if (!history.endTime) return { severity: 'info' as const, label: t('schedule.status.running') }
  return { severity: 'warn' as const, label: t('schedule.status.terminated') }
})

function resolvePublication(detail: CrontabHistoryDetail) {
  const status = detail.publicationStatus ?? 'LEGACY'
  return {
    severity: status === 'PUBLISHED' ? 'success' : status === 'FAILED' ? 'danger' : 'secondary',
    label: t(`schedule.details.publication.${status}`),
  }
}

function resolveVerification(detail: CrontabHistoryDetail) {
  const status = detail.verificationStatus ?? 'LEGACY'
  const severity =
    status === 'SOURCE_VERIFIED'
      ? 'success'
      : status === 'FAILED'
        ? 'danger'
        : status === 'TRANSPORT_VERIFIED'
          ? 'info'
          : 'secondary'
  return { severity, label: t(`schedule.details.verification.${status}`) }
}

function formatTime(value?: string) {
  if (!value) return '-'
  try {
    const date = new Date(value)
    if (Number.isNaN(date.getTime())) return value
    return date.toLocaleString(locale.value)
  } catch {
    return value
  }
}

function formatRange(history: CrontabHistory | null) {
  if (!history) return '-'
  return `${formatTime(history.startTime)} → ${formatTime(history.endTime || history.startTime)}`
}

function resolveErrorMessage(error: string | null | undefined) {
  return error?.trim() || t('schedule.details.loadFailed')
}

function resolveAssetId(detail: CrontabHistoryDetail) {
  return detail.asset?.id ?? '-'
}

function resolveAlbumName(detail: CrontabHistoryDetail) {
  return detail.asset?.album?.name?.trim() || '-'
}

function resolveAlbumId(detail: CrontabHistoryDetail) {
  return detail.asset?.album?.id ?? '-'
}

function resolveMessage(detail: CrontabHistoryDetail) {
  return detail.message?.trim() || '—'
}

function resolveStepStatus(value?: boolean) {
  return value
    ? { severity: 'success' as const, label: t('schedule.status.completed') }
    : { severity: 'warn' as const, label: t('schedule.status.incomplete') }
}

function handlePage(event: { page?: number; rows?: number }) {
  emit('page', {
    pageIndex: event.page ?? 0,
    pageSize: event.rows ?? props.pageSize,
  })
}

const stepTagClass = '!whitespace-nowrap !text-[13px]'
</script>

<template>
  <Dialog
    v-model:visible="visibleProxy"
    modal
    maximizable
    :style="dialogStyle"
    :pt="dialogPt"
    :header="t('schedule.details.title')"
    @maximize="maximized = true"
    @unmaximize="maximized = false"
  >
    <div class="flex min-h-0 flex-1 flex-col gap-4 pt-2">
      <div
        class="flex flex-col gap-3 rounded-lg bg-slate-50 dark:bg-slate-900/40 ring-1 ring-slate-200/70 dark:ring-slate-700/70 p-4"
      >
        <div class="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
          <div class="space-y-2 min-w-0">
            <div class="flex items-center gap-2 flex-wrap">
              <span class="text-sm font-medium text-slate-700 dark:text-slate-200">
                {{ formatRange(history) }}
              </span>
              <Tag :severity="historyStatus.severity" :value="historyStatus.label" />
            </div>
            <div
              class="text-[13px] text-slate-500 dark:text-slate-400 flex flex-wrap gap-x-4 gap-y-1"
            >
              <span>{{ t('schedule.details.historyId', { id: history?.id ?? '-' }) }}</span>
              <span>{{ t('schedule.details.totalCount', { n: totalRowCount }) }}</span>
              <span>{{ t('schedule.details.pageSize', { n: pageSize }) }}</span>
            </div>
          </div>

          <Button
            v-if="historyStatus.severity === 'info'"
            :label="t('schedule.details.manualRefresh')"
            icon="pi pi-refresh"
            size="small"
            severity="secondary"
            :loading="loading"
            @click="emit('refresh')"
          />
        </div>

        <div
          v-if="history?.deletionReport"
          class="space-y-1 text-sm text-slate-600 dark:text-slate-300"
        >
          <p>
            {{
              t('schedule.details.deletionStatus', {
                status: t(`schedule.details.deletionStates.${history.deletionReport.status}`),
              })
            }}
          </p>
          <p>
            {{
              t('schedule.details.deletionSummary', {
                exports: history.deletionReport.exportsDeleted,
                originals: history.deletionReport.originalsDeleted,
                deferred: history.deletionReport.deferredFiles,
                conflicts: history.deletionReport.conflictCount,
              })
            }}
          </p>
          <p v-if="history.deletionReport.message">{{ history.deletionReport.message }}</p>
          <div v-for="result in history.deletionReport.results" :key="result.groupKey">
            <p v-for="message in result.conflicts" :key="message">{{ message }}</p>
          </div>
        </div>

        <div
          v-if="error"
          class="rounded-md bg-red-50 dark:bg-red-950/40 text-red-700 dark:text-red-300 text-sm px-3 py-2 ring-1 ring-red-200 dark:ring-red-900/60"
        >
          {{ resolveErrorMessage(error) }}
        </div>
      </div>

      <div class="min-h-0 flex-1">
        <DataTable
          :value="rows"
          :loading="loading"
          :rows="pageSize"
          :first="first"
          :total-records="totalRowCount"
          paginator
          lazy
          scrollable
          scroll-height="flex"
          removable-sort
          size="small"
          class="h-full text-sm"
          @page="handlePage"
        >
          <template #empty>
            <div class="text-center text-slate-500 dark:text-slate-400 py-6">
              {{ error ? t('schedule.details.loadFailedRetry') : t('schedule.details.empty') }}
            </div>
          </template>

          <Column :header="t('schedule.details.colAsset')" style="min-width: 12rem">
            <template #body="{ data }">
              <span class="font-mono text-sm text-slate-700 dark:text-slate-200">
                {{ resolveAssetId(data) }}
              </span>
            </template>
          </Column>

          <Column :header="t('schedule.details.colAlbum')" style="min-width: 10rem">
            <template #body="{ data }">
              <div class="flex flex-col gap-1">
                <span class="text-slate-700 dark:text-slate-200">
                  {{ resolveAlbumName(data) }}
                </span>
                <span class="font-mono text-[13px] text-slate-500 dark:text-slate-400">
                  {{ 'ID ' + resolveAlbumId(data) }}
                </span>
              </div>
            </template>
          </Column>

          <Column :header="t('schedule.details.colFilePath')" style="min-width: 18rem">
            <template #body="{ data }">
              <span class="font-mono text-[13px] text-slate-600 dark:text-slate-300 break-all">
                {{ data.filePath || '-' }}
              </span>
            </template>
          </Column>

          <Column :header="t('schedule.details.colDownloadTime')" style="min-width: 11rem">
            <template #body="{ data }">
              <span class="text-slate-600 dark:text-slate-300">
                {{ formatTime(data.downloadTime) }}
              </span>
            </template>
          </Column>

          <Column :header="t('schedule.details.colDownload')" style="width: 6rem">
            <template #body="{ data }">
              <Tag
                :class="stepTagClass"
                :severity="resolveStepStatus(data.downloadCompleted).severity"
                :value="resolveStepStatus(data.downloadCompleted).label"
              />
            </template>
          </Column>

          <Column :header="t('schedule.details.colSha1')" style="min-width: 9rem">
            <template #body="{ data }">
              <Tag
                :class="stepTagClass"
                :severity="resolveVerification(data).severity"
                :value="resolveVerification(data).label"
                :title="data.localSha256 ? `SHA-256: ${data.localSha256}` : undefined"
              />
            </template>
          </Column>

          <Column :header="t('schedule.details.colPublication')" style="min-width: 8rem">
            <template #body="{ data }">
              <Tag
                :severity="resolvePublication(data).severity"
                :value="resolvePublication(data).label"
                :title="data.outputSha256 ? `SHA-256: ${data.outputSha256}` : undefined"
              />
            </template>
          </Column>

          <Column header="EXIF" style="width: 6rem">
            <template #body="{ data }">
              <Tag
                :class="stepTagClass"
                :severity="resolveStepStatus(data.exifFilled).severity"
                :value="resolveStepStatus(data.exifFilled).label"
              />
            </template>
          </Column>

          <Column :header="t('schedule.details.colFsTime')" style="width: 7rem">
            <template #body="{ data }">
              <Tag
                :class="stepTagClass"
                :severity="resolveStepStatus(data.fsTimeUpdated).severity"
                :value="resolveStepStatus(data.fsTimeUpdated).label"
              />
            </template>
          </Column>

          <Column :header="t('schedule.details.colMessage')" style="min-width: 16rem">
            <template #body="{ data }">
              <div class="text-sm text-slate-700 dark:text-slate-200 break-words">
                {{ resolveMessage(data) }}
              </div>
            </template>
          </Column>
        </DataTable>
      </div>
    </div>
  </Dialog>
</template>
