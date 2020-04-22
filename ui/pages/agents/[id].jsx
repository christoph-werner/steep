import DetailPage from "../../components/layouts/DetailPage"
import Link from "next/link"
import { useRouter } from "next/router"
import { useContext, useEffect, useState } from "react"
import Alert from "../../components/Alert"
import DefinitionList from "../../components/DefinitionList"
import DefinitionListItem from "../../components/DefinitionListItem"
import Label from "../../components/Label"
import ListItemProgressBox from "../../components/ListItemProgressBox"
import LiveDuration from "../../components/LiveDuration"
import AgentContext from "../../components/agents/AgentContext"
import { formatDate } from "../../components/lib/date-time-utils"
import agentToProgress from "../../components/agents/agent-to-progress"
import fetcher from "../../components/lib/json-fetcher"

function Agent({ id }) {
  const agents = useContext(AgentContext.State)
  const updateAgents = useContext(AgentContext.Dispatch)
  const [error, setError] = useState()

  useEffect(() => {
    if (id) {
      fetcher(`${process.env.baseUrl}/agents/${id}`)
        .then(agent => updateAgents({ action: "push", agents: [agent] }))
        .catch(err => {
          console.log(err)
          setError(<Alert error>Could not load agent</Alert>)
        })
    }
  }, [id, updateAgents])

  let breadcrumbs
  let title
  let agent

  if (typeof agents !== "undefined" && agents.length > 0) {
    let a = agents[0]
    title = a.id
    breadcrumbs = [
      <Link href="/agents" key="agents"><a>Agents</a></Link>,
      a.id
    ]

    let caps
    if (typeof a.capabilities === "undefined" || a.capabilities.length === 0) {
      caps = <>&ndash;</>
    } else {
      caps = a.capabilities.map((r, i) => <Label key={i}>{r}</Label>)
    }

    let progress = agentToProgress(a)

    agent = (<>
      <div className="detail-header">
        <div className="detail-header-left">
          <DefinitionList>
            <DefinitionListItem title="Start time">
              {a.startTime ? formatDate(a.startTime) : <>&ndash;</>}
            </DefinitionListItem>
            <DefinitionListItem title="Utime">
              {a.startTime ? <LiveDuration startTime={a.startTime} /> : <>&ndash;</>}
            </DefinitionListItem>
            <DefinitionListItem title="Capabilities">
              {caps}
            </DefinitionListItem>
          </DefinitionList>
        </div>
        <div className="detail-header-right">
          <ListItemProgressBox progress={progress} />
        </div>
      </div>
    </>)
  }

  return (
    <DetailPage breadcrumbs={breadcrumbs} title={title}>
      {agent}
      {error}
    </DetailPage>
  )
}

export default () => {
  const router = useRouter()
  const { id } = router.query

  return (
    <AgentContext.Provider pageSize={1} allowAdd={false}>
      <Agent id={id} />
    </AgentContext.Provider>
  )
}
